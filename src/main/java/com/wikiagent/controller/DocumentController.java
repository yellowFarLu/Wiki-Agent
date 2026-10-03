package com.wikiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.application.knowledge.KnowledgeTagContext;
import com.wikiagent.application.task.TaskQueryService;
import com.wikiagent.application.task.TaskSubmissionService;
import com.wikiagent.domain.identity.BusinessIdentity;
import com.wikiagent.domain.identity.DomainTag;
import com.wikiagent.domain.identity.SubDomainTag;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskPayload;
import com.wikiagent.dto.DocumentView;
import com.wikiagent.dto.NotFoundException;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.service.ingest.IngestionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** 文档管理：上传（触发异步入库）、列表、详情、删除。 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KbDocumentRepo docRepo;
    private final IngestionService ingestionService;
    private final ObjectProvider<TaskSubmissionService> submission;
    private final ObjectProvider<TaskQueryService> taskQuery;

    public DocumentController(KbDocumentRepo docRepo, IngestionService ingestionService,
                              ObjectProvider<TaskSubmissionService> submission,
                              ObjectProvider<TaskQueryService> taskQuery) {
        this.docRepo = docRepo;
        this.ingestionService = ingestionService;
        this.submission = submission;
        this.taskQuery = taskQuery;
    }

    @PostMapping
    public DocumentView upload(@RequestParam("file") MultipartFile file,
                               @RequestParam(required = false) String domain,
                               @RequestParam(required = false) String subDomain,
                               @RequestParam(required = false) String effectiveDate,
                               @RequestHeader(value = "X-User-Id", required = false) String userId,
                               @RequestHeader(value = "X-Business-Identity", required = false) String identity)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String filename = sanitizeFilename(file.getOriginalFilename());
        String ext = extensionOf(filename);
        if (ext.isBlank()) {
            throw new IllegalArgumentException("无法识别文件扩展名，支持 txt/md/pdf/docx/xlsx");
        }

        // v4 §6.6.1 打标参数校验：显式传入时必须是合法枚举 code
        if (domain != null && DomainTag.fromCode(domain) == null) {
            throw new IllegalArgumentException("非法领域标签 domain=" + domain);
        }
        if (subDomain != null && SubDomainTag.fromCode(subDomain) == null) {
            throw new IllegalArgumentException("非法知识类型标签 subDomain=" + subDomain);
        }
        if (identity != null && !isValidIdentity(identity)) {
            throw new IllegalArgumentException("非法业务身份 identity=" + identity);
        }
        // 子项目 G：生效日期解析（冲突裁决依据）；缺省=服务器当前日期，非法格式 → 400
        LocalDate effective = resolveEffectiveDate(effectiveDate);

        String owner = userId == null ? "anonymous" : userId;
        byte[] bytes = file.getBytes();

        // 任务框架路径：bizKey=ingest:{sha256}:{userId} 幂等提交，六步流水线异步执行
        if (submission.getIfAvailable() != null) {
            return uploadViaTask(bytes, filename, ext, owner,
                    domain, subDomain, identity, effective);
        }

        // 旧异步路径（task.enabled=false）：@Async 入库，行为不变
        KnowledgeTagContext tagContext = (domain != null && subDomain != null)
                ? new KnowledgeTagContext(domain, subDomain,
                    identity == null ? "business" : identity,
                    owner,
                    identity == null ? "business" : identity,
                    filename)
                : KnowledgeTagContext.defaultFor(filename);

        String docId = UUID.randomUUID().toString();
        Path dir = Path.of("data", "uploads", docId);
        Files.createDirectories(dir);
        Files.write(dir.resolve(filename), bytes);

        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setFilename(filename);
        doc.setDocType(ext);
        doc.setSizeBytes(bytes.length);
        doc.setStatus(KbDocument.PARSING);
        // 同步路径直接落库生效日期三要素
        doc.setEffectiveDate(effective);
        doc.setEffectiveSetBy(owner);
        doc.setEffectiveSetAt(Instant.now());
        docRepo.save(doc);

        ingestionService.ingest(docId, filename, bytes, tagContext);
        return DocumentView.from(doc);
    }

    /** 任务框架路径：同内容同人重复上传 → 命中 bizKey 幂等，返回既有 taskId 不重复入库。 */
    private DocumentView uploadViaTask(byte[] bytes, String filename, String ext, String owner,
                                       String domain, String subDomain, String identity,
                                       LocalDate effective) throws IOException {
        String docId = UUID.randomUUID().toString();
        String bizKey = "ingest:" + sha256Hex(bytes) + ":" + owner;
        var existing = taskQueryAvailable().findByBizKey(bizKey);
        if (existing.isPresent()) {
            TaskInstance task = existing.get();
            String origDocId = task.payload() == null ? null : task.payload().path("docId").asText(null);
            KbDocument orig = origDocId == null ? null : docRepo.findById(origDocId).orElse(null);
            if (orig != null && !KbDocument.FAILED.equals(orig.getStatus())) {
                return DocumentView.of(orig, task.taskId(), true);
            }
            // 首次入库失败或原文档已删：以新 docId 派生新 bizKey 重新入库。
            // 若沿用旧 bizKey，submit 会幂等返回已终态（FAILED/CANCELLED）的旧任务，新文档永远无人执行
            bizKey = bizKey + ":retry:" + docId;
        }

        Path dir = Path.of("data", "uploads", docId);
        Files.createDirectories(dir);
        Files.write(dir.resolve(filename), bytes);

        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setFilename(filename);
        doc.setDocType(ext);
        doc.setSizeBytes(bytes.length);
        doc.setStatus(KbDocument.PARSING);
        docRepo.save(doc);

        ObjectNode args = MAPPER.createObjectNode()
                .put("docId", docId)
                .put("filename", filename)
                .put("userId", owner)
                // 生效日期经 payload 透传，由 IngestTaskHandler 落库（setBy=userId）
                .put("effectiveDate", effective.toString());
        if (domain != null) {
            args.put("domain", domain);
        }
        if (subDomain != null) {
            args.put("subDomain", subDomain);
        }
        if (identity != null) {
            args.put("identity", identity);
        }
        TaskInstance task = submissionAvailable()
                .submit(new TaskPayload("INGEST", bizKey, owner, null, null, args, null, null));
        return DocumentView.of(doc, task.taskId(), false);
    }

    private TaskQueryService taskQueryAvailable() {
        TaskQueryService s = taskQuery.getIfAvailable();
        if (s == null) {
            throw new IllegalStateException("任务框架未开启（wikiagent.task.enabled=false）");
        }
        return s;
    }

    private TaskSubmissionService submissionAvailable() {
        TaskSubmissionService s = submission.getIfAvailable();
        if (s == null) {
            throw new IllegalStateException("任务框架未开启（wikiagent.task.enabled=false）");
        }
        return s;
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

    /** 生效日期解析：ISO-8601 yyyy-MM-dd；缺省/空白 → 服务器当前日期；非法格式 → IllegalArgumentException(400)。 */
    private static LocalDate resolveEffectiveDate(String effectiveDate) {
        if (effectiveDate == null || effectiveDate.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(effectiveDate.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "非法生效日期格式（应为 yyyy-MM-dd）: " + effectiveDate, e);
        }
    }

    private static boolean isValidIdentity(String code) {
        for (BusinessIdentity i : BusinessIdentity.values()) {
            if (i.code().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    @GetMapping
    public List<DocumentView> list() {
        return docRepo.findAllByOrderByCreatedAtDesc().stream().map(DocumentView::from).toList();
    }

    @GetMapping("/{id}")
    public DocumentView detail(@PathVariable String id) {
        KbDocument doc = docRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("文档不存在: " + id));
        return DocumentView.from(doc);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        if (!docRepo.existsById(id)) {
            throw new NotFoundException("文档不存在: " + id);
        }
        ingestionService.delete(id);
    }

    private static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("文件名为空");
        }
        // 去掉可能的路径成分，避免目录穿越
        String name = Path.of(original.replace("\\", "/")).getFileName().toString().strip();
        if (name.isEmpty() || name.startsWith(".")) {
            throw new IllegalArgumentException("非法文件名: " + original);
        }
        return name;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }
}
