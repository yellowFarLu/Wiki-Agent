package com.wikiagent.application.task.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.application.knowledge.KnowledgeTagContext;
import com.wikiagent.application.parse.ParseInputValidator;
import com.wikiagent.domain.parse.EncryptedDocumentException;
import com.wikiagent.domain.parse.model.DocKind;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.RetryableTaskException;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.domain.task.TaskHandler;
import com.wikiagent.domain.extract.ExtractionReport;
import com.wikiagent.service.ingest.IngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档入库任务处理器（taskType=INGEST）。
 * <p>
 * 【边界标记】{@link com.wikiagent.domain.task.BoundaryType#FIXED_HANDLER} — 固定步骤处理器，
 * 步骤号冻结（1-6 + 100+），不走 ReAct，不涉及 LLM 自主决策。
 * <p>
 * 基础六步（所有介质）：DOWNLOAD → PARSE → CLEAN → SPLIT → EMBED_AND_PERSIST → INDEX_VERIFY。
 * 条件动态步骤（大编号段 100+，沿用 AgentTaskHandler 模式）：
 * <ul>
 *   <li>100 LAYOUT_TABLE：PDF 版面分析 + 跨页表格拼接（B3）；</li>
 *   <li>110 EXTRACT：按 payload.extractionSchema 结构化字段抽取（B4），低置信/校验失败转 REVIEW。</li>
 * </ul>
 * PARSE 异常分类：不支持/未知扩展 → Fatal(VALIDATION_FAILED)；损坏 → Fatal(PARSE_FAILED)；
 * 加密 PDF → HumanRequiredException(DECRYPT)（口令字段 decryptPassword，人工填表续跑）；
 * AI 依赖介质供应商未配置 → 文档置 AI_SKIPPED 终态，不静默产出空 chunk。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class IngestTaskHandler implements TaskHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final int STEP_LAYOUT_TABLE = 100;
    public static final int STEP_EXTRACT = 110;

    private final IngestionService ingestion;
    private final ParseInputValidator validator;
    private final org.springframework.beans.factory.ObjectProvider<com.wikiagent.domain.task.ports.HumanTaskRepositoryPort> humanTaskRepo;
    private final org.springframework.beans.factory.ObjectProvider<com.wikiagent.application.rule.ReviewCaseService> reviewCaseService;

    /** 自动装配构造器：含介质判定器 + D 复核接线（ObjectProvider 懒获取，不影响无 D 依赖的测试）。 */
    @Autowired
    public IngestTaskHandler(IngestionService ingestion, ParseInputValidator validator,
                             org.springframework.beans.factory.ObjectProvider<com.wikiagent.domain.task.ports.HumanTaskRepositoryPort> humanTaskRepo,
                             org.springframework.beans.factory.ObjectProvider<com.wikiagent.application.rule.ReviewCaseService> reviewCaseService) {
        this.ingestion = ingestion;
        this.validator = validator;
        this.humanTaskRepo = humanTaskRepo;
        this.reviewCaseService = reviewCaseService;
    }

    /** 测试用构造器：仅基础六步路径，不触发条件化分支。 */
    public IngestTaskHandler(IngestionService ingestion, ParseInputValidator validator) {
        this(ingestion, validator, null, null);
    }

    /** 测试用构造器：仅基础六步路径，不触发条件化分支。 */
    public IngestTaskHandler(IngestionService ingestion) {
        this(ingestion, null);
    }

    @Override
    public String taskType() {
        return "INGEST";
    }

    /**
     * 任务终态失败钩子：把文档置 FAILED 并记录错误，避免前端列表长期卡在中间态。
     * 钩子由 TaskWorker.failTask 在 RETRY 耗尽或 Fatal 时触发。
     */
    @Override
    public void onFailed(TaskExecutionContext ctx, ErrorCode code, String msg) {
        String docId = ctx.args().path("docId").asText(null);
        if (docId == null || docId.isBlank()) {
            return;
        }
        ingestion.markFailedStep(docId, code.name() + ": " + (msg == null ? "任务失败" : msg));
    }

    @Override
    public List<StepDef> planSteps(JsonNode payloadArgs) {
        List<StepDef> steps = new ArrayList<>(List.of(
                StepDef.of(1, "DOWNLOAD", "读取上传文件"),
                StepDef.of(2, "PARSE", "解析文档"),
                StepDef.of(3, "CLEAN", "清洗文本"),
                StepDef.of(4, "SPLIT", "父子切分"),
                StepDef.of(5, "EMBED_AND_PERSIST", "向量化与索引"),
                StepDef.of(6, "INDEX_VERIFY", "索引校验")));
        if (validator != null && payloadArgs != null) {
            String filename = payloadArgs.path("filename").asText(null);
            if (filename != null && validator.kindOf(filename) == DocKind.PDF) {
                steps.add(StepDef.of(STEP_LAYOUT_TABLE, "LAYOUT_TABLE", "版面分析与跨页表格拼接"));
            }
            if (payloadArgs.hasNonNull("extractionSchema")) {
                steps.add(StepDef.of(STEP_EXTRACT, "EXTRACT", "结构化字段抽取"));
            }
        }
        return steps;
    }

    @Override
    public StepResult executeStep(TaskExecutionContext ctx)
            throws RetryableTaskException, FatalTaskException, HumanRequiredException {
        JsonNode args = ctx.args();
        String docId = args.path("docId").asText();
        String filename = args.path("filename").asText();
        return switch (ctx.currentStepNo()) {
            case 1 -> download(docId, filename, args);
            case 2 -> parse(ctx, docId, filename);
            case 3 -> clean(docId);
            case 4 -> split(args, docId, filename);
            case 5 -> embed(docId);
            case 6 -> verify(docId);
            case STEP_LAYOUT_TABLE -> layout(docId, filename);
            case STEP_EXTRACT -> extract(ctx, docId, args.path("extractionSchema").asText());
            default -> throw new FatalTaskException(ErrorCode.INTERNAL,
                    "未知入库步骤: " + ctx.currentStepNo());
        };
    }

    private StepResult download(String docId, String filename, JsonNode args) {
        Path file = uploadPath(docId, filename);
        if (!Files.exists(file)) {
            throw new FatalTaskException(ErrorCode.VALIDATION_FAILED, "上传文件不存在: " + file);
        }
        applyEffectiveDate(docId, args);
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                throw new FatalTaskException(ErrorCode.VALIDATION_FAILED, "上传文件为空: " + filename);
            }
            ObjectNode cp = MAPPER.createObjectNode()
                    .put("sha256", sha256Hex(bytes))
                    .put("sizeBytes", bytes.length);
            return new StepResult(false, cp.toString(), 10, null);
        } catch (FatalTaskException e) {
            throw e;
        } catch (Exception e) {
            throw new FatalTaskException(ErrorCode.INTERNAL, "读取上传文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 子项目 G Task 8：payload.effectiveDate 落库（任务框架路径由 controller 透传）。
     * 解析失败视为不可重试的数据校验错误（VALIDATION_FAILED），不阻断文件存在性校验语义。
     */
    private void applyEffectiveDate(String docId, JsonNode args) {
        String text = args == null ? null : args.path("effectiveDate").asText(null);
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            ingestion.applyEffectiveDateStep(docId, text, args.path("userId").asText("anonymous"));
        } catch (IllegalArgumentException e) {
            throw new FatalTaskException(ErrorCode.VALIDATION_FAILED, e.getMessage(), e);
        }
    }

    private StepResult parse(TaskExecutionContext ctx, String docId, String filename) {
        try {
            byte[] bytes = Files.readAllBytes(uploadPath(docId, filename));
            String password = decryptPassword(ctx);
            ParsedDocument parsed = ingestion.richParseStep(docId, filename, bytes, password);
            // 全文写 _parsed.txt（兼容 CLEAN 旧链路）；富结构写 _parsed.json（版面/抽取复用）
            Files.writeString(parsedPath(docId), parsed.fullText() == null ? "" : parsed.fullText(),
                    StandardCharsets.UTF_8);
            MAPPER.writeValue(parsedJsonPath(docId).toFile(), parsed);
            if (parsed.aiSkipped()) {
                // AI 依赖介质供应商未配置：终态 AI_SKIPPED，不进入 CLEAN/SPLIT
                ingestion.aiSkipStep(docId);
                return new StepResult(true, MAPPER.createObjectNode()
                        .put("aiSkipped", true).toString(), 100, "kb-doc:" + docId);
            }
            return StepResult.done(25);
        } catch (EncryptedDocumentException e) {
            // 加密 PDF：建 DECRYPT 人工任务，口令字段 decryptPassword，人工填表后续跑
            throw new HumanRequiredException(HumanTaskKind.DECRYPT,
                    "加密文档需要打开口令",
                    e.getMessage(),
                    decryptFormSchema(e.passwordRejected()));
        } catch (IllegalArgumentException e) {
            throw new FatalTaskException(ErrorCode.VALIDATION_FAILED, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new FatalTaskException(ErrorCode.PARSE_FAILED, e.getMessage(), e);
        } catch (FatalTaskException e) {
            throw e;
        } catch (Exception e) {
            throw new FatalTaskException(ErrorCode.INTERNAL, "解析步骤失败: " + e.getMessage(), e);
        }
    }

    private StepResult layout(String docId, String filename) {
        if (aiSkipped(docId)) {
            return StepResult.skipped(); // AI 跳过文档不做版面增强
        }
        try {
            byte[] bytes = Files.readAllBytes(uploadPath(docId, filename));
            ingestion.structureStep(docId, bytes, null);
            return StepResult.done(30);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 产物缺失/反序列化/本地 IO 一致性错误：重试无意义
            throw new FatalTaskException(ErrorCode.PARSE_FAILED, e.getMessage(), e);
        } catch (Exception e) {
            // 供应商瞬时故障（超时/熔断，PageStructureService 未吞尽时）可重试
            throw new RetryableTaskException(ErrorCode.THIRD_PARTY_5XX,
                    "版面分析步骤失败: " + e.getMessage(), e);
        }
    }

    private StepResult extract(TaskExecutionContext ctx, String docId, String schemaKey)
            throws HumanRequiredException {
        if (aiSkipped(docId)) {
            return StepResult.skipped(); // AI 跳过文档不做字段抽取
        }
        if (schemaKey == null || schemaKey.isBlank()) {
            return StepResult.skipped();
        }
        // D 钩子①：若本任务已有 RESOLVED 的 REVIEW 人工任务（字段已人工定稿），跳过 LLM 抽取
        if (humanTaskRepo != null) {
            var repo = humanTaskRepo.getIfAvailable();
            if (repo != null) {
                boolean reviewed = repo.findByTaskId(ctx.taskId()).stream()
                        .anyMatch(h -> h.kind() == HumanTaskKind.REVIEW
                                && h.status() == com.wikiagent.domain.task.HumanTaskStatus.RESOLVED);
                if (reviewed) {
                    return StepResult.done(95);
                }
            }
        }
        ExtractionReport report = ingestion.extractStep(docId, schemaKey);
        if (report.needsReview()) {
            // D 钩子②：每个 invalid 或低置信字段建 LOW_CONFIDENCE 复核案件（taskId 关联）
            if (reviewCaseService != null) {
                var svc = reviewCaseService.getIfAvailable();
                if (svc != null) {
                    for (var field : report.fields()) {
                        boolean lowConf = field.lowConfidence(0.75);
                        if (!field.valid() || lowConf) {
                            svc.createLowConfidence(docId, null, field.key(),
                                    field.source() == null ? null : field.source().name(),
                                    field.confidence(),
                                    String.join("; ", field.errors()), ctx.taskId());
                        }
                    }
                }
            }
            // 低置信/校验失败：建 REVIEW 人工任务，由人工复核/修正字段（审批语义，不依赖表单值续跑）
            throw new HumanRequiredException(HumanTaskKind.REVIEW,
                    "字段抽取结果需要人工复核",
                    String.join("\n", report.reviewReasons()),
                    null);
        }
        ObjectNode cp = MAPPER.createObjectNode()
                .put("schemaKey", report.schemaKey())
                .put("schemaVersion", report.schemaVersion())
                .put("fieldCount", report.fields().size());
        return new StepResult(false, cp.toString(), 95, null);
    }

    private StepResult clean(String docId) {
        if (aiSkipped(docId)) {
            return StepResult.skipped(); // AI_SKIPPED 终态：不清洗/切分/索引
        }
        try {
            String raw = Files.readString(parsedPath(docId), StandardCharsets.UTF_8);
            String cleaned = ingestion.cleanStep(docId, raw);
            Files.writeString(cleanedPath(docId), cleaned, StandardCharsets.UTF_8);
            return StepResult.done(40);
        } catch (Exception e) {
            throw new FatalTaskException(ErrorCode.INTERNAL, "清洗步骤失败: " + e.getMessage(), e);
        }
    }

    private StepResult split(JsonNode args, String docId, String filename) {
        if (aiSkipped(docId)) {
            return StepResult.skipped();
        }
        try {
            String cleaned = Files.readString(cleanedPath(docId), StandardCharsets.UTF_8);
            IngestionService.IngestOutcome outcome =
                    ingestion.splitStep(docId, cleaned, tagContextOf(args, filename));
            ObjectNode cp = MAPPER.createObjectNode()
                    .put("parentCount", outcome.parentCount())
                    .put("childCount", outcome.childCount());
            return new StepResult(false, cp.toString(), 55, null);
        } catch (FatalTaskException e) {
            throw e;
        } catch (Exception e) {
            throw new FatalTaskException(ErrorCode.INTERNAL, "切分步骤失败: " + e.getMessage(), e);
        }
    }

    /** Milvus/向量化瞬时异常不上抛致命——交由 worker 按 INTERNAL 重试退避。 */
    private StepResult embed(String docId) {
        if (aiSkipped(docId)) {
            return StepResult.skipped();
        }
        boolean persisted = ingestion.embedAndPersistStep(docId);
        return persisted ? StepResult.done(85) : StepResult.skipped();
    }

    private StepResult verify(String docId) {
        if (aiSkipped(docId)) {
            // AI_SKIPPED 已是文档终态：不置 READY，任务正常收尾（COMPLETED）
            return StepResult.done(100, "kb-doc:" + docId);
        }
        ingestion.finalizeStep(docId);
        return StepResult.done(100, "kb-doc:" + docId);
    }

    /** 读 PARSE 写出的 _parsed.json 判定 AI 跳过终态（worker skip 不存 checkpoint，故用文件标记）。 */
    private boolean aiSkipped(String docId) {
        try {
            Path p = parsedJsonPath(docId);
            return Files.exists(p) && MAPPER.readTree(p.toFile()).path("aiSkipped").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private String decryptPassword(TaskExecutionContext ctx) {
        JsonNode v = ctx.humanInputs().get("decryptPassword");
        return v == null || v.isNull() ? null : v.asText(null);
    }

    private JsonNode decryptFormSchema(boolean rejected) {
        ObjectNode field = MAPPER.createObjectNode()
                .put("key", "decryptPassword")
                .put("label", "文档打开口令")
                .put("type", "password")
                .put("required", true)
                .put("placeholder", rejected ? "口令不正确，请重新输入" : "请输入加密文档的打开口令");
        return MAPPER.createObjectNode().set("fields", MAPPER.createArrayNode().add(field));
    }

    private KnowledgeTagContext tagContextOf(JsonNode args, String filename) {
        String domain = args.path("domain").asText(null);
        String subDomain = args.path("subDomain").asText(null);
        if (domain == null || domain.isBlank() || subDomain == null || subDomain.isBlank()) {
            return KnowledgeTagContext.defaultFor(filename);
        }
        String identity = args.path("identity").asText("business");
        String userId = args.path("userId").asText("anonymous");
        return new KnowledgeTagContext(domain, subDomain, identity, userId, identity, filename);
    }

    private static Path uploadPath(String docId, String filename) {
        return Path.of("data", "uploads", docId, filename);
    }

    private static Path parsedPath(String docId) {
        return Path.of("data", "uploads", docId, "_parsed.txt");
    }

    private static Path parsedJsonPath(String docId) {
        return Path.of("data", "uploads", docId, "_parsed.json");
    }

    private static Path cleanedPath(String docId) {
        return Path.of("data", "uploads", docId, "_cleaned.txt");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
