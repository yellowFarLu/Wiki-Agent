package com.wikiagent.service.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.extract.FieldExtractionService;
import com.wikiagent.application.knowledge.KnowledgeTagContext;
import com.wikiagent.application.knowledge.KnowledgeTaggingService;
import com.wikiagent.application.knowledge.dedup.NearDuplicateGuard;
import com.wikiagent.application.lineage.ProvenanceService;
import com.wikiagent.application.parse.PageStructureService;
import com.wikiagent.application.parse.RichDocumentParser;
import com.wikiagent.application.ragcache.EmbeddingCacheService;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.extract.ExtractedFieldValue;
import com.wikiagent.domain.extract.ExtractionReport;
import com.wikiagent.domain.lineage.ArtifactType;
import com.wikiagent.domain.lineage.DocArtifact;
import com.wikiagent.domain.lineage.DocVersion;
import com.wikiagent.domain.lineage.EdgeType;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.parse.model.ParsedPage;
import com.wikiagent.infrastructure.lineage.ArtifactStore;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.store.MilvusStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 入库流水线：解析 → 清洗 → 父/子切分 → 子块向量化 → 写 Milvus。
 * 状态机记录在 kb_document.status，前端轮询展示进度。
 * <p>
 * 两种驱动方式：
 * <ul>
 *   <li>任务框架（task.enabled=true）：{@code IngestTaskHandler} 按步骤调用各 Step 方法，断点续跑；</li>
 *   <li>旧异步入口 {@code @Async ingest(...)}：顺序组合 {@link #runPipeline}，异常兜底置 FAILED（enabled=false 或兼容路径）。</li>
 * </ul>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final WikiAgentProperties props;
    private final DocumentParser parser;
    private final TextCleaner cleaner;
    private final RichDocumentParser richParser;
    private final PageStructureService structureService;
    private final FieldExtractionService extractionService;
    private final ProvenanceService provenance;
    private final ArtifactStore artifactStore;
    private final KbDocumentRepo docRepo;
    private final KbParentChunkRepo parentRepo;
    private final KbChildChunkRepo childRepo;
    private final MilvusStoreService milvus;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeTaggingService taggingService;
    /** GraphRAG 提取服务（可选，wikiagent.graph.enabled=true 时注入）。 */
    private final com.wikiagent.application.graph.GraphExtractionService graphExtractionService;
    /**
     * 缺陷14：embedding 精确缓存（可选）。存在且 active 时批量 embed 先查缓存，
     * 开关关闭（wikiagent.cache.embedding.enabled=false）或 Redis 缺席时由其内部 bypass 直调模型。
     */
    private final EmbeddingCacheService embeddingCache;
    /** 缓存 key 中的模型名，与 DashScope embedding 配置一致。 */
    private final String embeddingModelName;
    /** L0 精确去重开关（wikiagent.dedup.enabled，默认 false）。 */
    private final boolean dedupEnabled;
    /** L0 精确去重打点 DAO（metric_event.chunkId 用真实新 chunk id）。 */
    private final MetricEventJpaDao metricEventDao;
    /** L1 近重复守卫（可选，wikiagent.dedup.enabled=true 时生效；embedAndPersistStep 开头调用）。 */
    private final NearDuplicateGuard nearDuplicateGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 兼容旧装配（既有测试直接 new）：不接 embedding 缓存，裸调 embeddingModel。 */
    public IngestionService(WikiAgentProperties props, DocumentParser parser, TextCleaner cleaner,
                            RichDocumentParser richParser, PageStructureService structureService,
                            FieldExtractionService extractionService,
                            ProvenanceService provenance, ArtifactStore artifactStore,
                            KbDocumentRepo docRepo, KbParentChunkRepo parentRepo, KbChildChunkRepo childRepo,
                            MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KnowledgeTaggingService taggingService) {
        this(props, parser, cleaner, richParser, structureService, extractionService, provenance,
                artifactStore, docRepo, parentRepo, childRepo, milvus, embeddingModel,
                taggingService, null, "text-embedding-v4", null, false, null, null);
    }

    /** 兼容旧装配（含 embedding 缓存）：无 GraphRAG。 */
    public IngestionService(WikiAgentProperties props, DocumentParser parser, TextCleaner cleaner,
                            RichDocumentParser richParser, PageStructureService structureService,
                            FieldExtractionService extractionService,
                            ProvenanceService provenance, ArtifactStore artifactStore,
                            KbDocumentRepo docRepo, KbParentChunkRepo parentRepo, KbChildChunkRepo childRepo,
                            MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KnowledgeTaggingService taggingService,
                            ObjectProvider<EmbeddingCacheService> embeddingCache,
                            @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v4}")
                            String embeddingModelName) {
        this(props, parser, cleaner, richParser, structureService, extractionService, provenance,
                artifactStore, docRepo, parentRepo, childRepo, milvus, embeddingModel,
                taggingService, embeddingCache, embeddingModelName, null, false, null, null);
    }

    /** 全量装配构造：末尾追加可选 {@link GraphExtractionService}（GraphRAG）与 L1 近重复守卫。 */
    @Autowired
    public IngestionService(WikiAgentProperties props, DocumentParser parser, TextCleaner cleaner,
                            RichDocumentParser richParser, PageStructureService structureService,
                            FieldExtractionService extractionService,
                            ProvenanceService provenance, ArtifactStore artifactStore,
                            KbDocumentRepo docRepo, KbParentChunkRepo parentRepo, KbChildChunkRepo childRepo,
                            MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KnowledgeTaggingService taggingService,
                            ObjectProvider<EmbeddingCacheService> embeddingCache,
                            @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v4}")
                            String embeddingModelName,
                            ObjectProvider<com.wikiagent.application.graph.GraphExtractionService> graphExtractionService,
                            @Value("${wikiagent.dedup.enabled:false}") boolean dedupEnabled,
                            ObjectProvider<MetricEventJpaDao> metricEventDao,
                            ObjectProvider<NearDuplicateGuard> nearDuplicateGuard) {
        this.props = props;
        this.parser = parser;
        this.cleaner = cleaner;
        this.richParser = richParser;
        this.structureService = structureService;
        this.extractionService = extractionService;
        this.provenance = provenance;
        this.artifactStore = artifactStore;
        this.docRepo = docRepo;
        this.parentRepo = parentRepo;
        this.childRepo = childRepo;
        this.milvus = milvus;
        this.embeddingModel = embeddingModel;
        this.taggingService = taggingService;
        this.embeddingCache = embeddingCache == null ? null : embeddingCache.getIfAvailable();
        this.embeddingModelName = embeddingModelName;
        this.graphExtractionService = graphExtractionService == null ? null : graphExtractionService.getIfAvailable();
        this.dedupEnabled = dedupEnabled;
        this.metricEventDao = metricEventDao == null ? null : metricEventDao.getIfAvailable();
        this.nearDuplicateGuard = nearDuplicateGuard == null ? null : nearDuplicateGuard.getIfAvailable();
    }

    /** 单测用装配：显式指定 dedup 开关与 metric DAO。 */
    public IngestionService(WikiAgentProperties props, DocumentParser parser, TextCleaner cleaner,
                            RichDocumentParser richParser, PageStructureService structureService,
                            FieldExtractionService extractionService,
                            ProvenanceService provenance, ArtifactStore artifactStore,
                            KbDocumentRepo docRepo, KbParentChunkRepo parentRepo, KbChildChunkRepo childRepo,
                            MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KnowledgeTaggingService taggingService,
                            boolean dedupEnabled, MetricEventJpaDao metricEventDao) {
        this.props = props;
        this.parser = parser;
        this.cleaner = cleaner;
        this.richParser = richParser;
        this.structureService = structureService;
        this.extractionService = extractionService;
        this.provenance = provenance;
        this.artifactStore = artifactStore;
        this.docRepo = docRepo;
        this.parentRepo = parentRepo;
        this.childRepo = childRepo;
        this.milvus = milvus;
        this.embeddingModel = embeddingModel;
        this.taggingService = taggingService;
        this.embeddingCache = null;
        this.embeddingModelName = "text-embedding-v4";
        this.graphExtractionService = null;
        this.dedupEnabled = dedupEnabled;
        this.metricEventDao = metricEventDao;
        this.nearDuplicateGuard = null;
    }

    /** 入库结果摘要。 */
    public record IngestOutcome(int parentCount, int childCount) {
    }

    // ===== 同步阶段方法（任务 handler 逐步调用；runPipeline 顺序组合）=====

    /** 步骤 2 解析：置 PARSING，按扩展名解析为纯文本。不支持/损坏的异常由调用方分类。 */
    public String parseStep(String docId, String filename, byte[] bytes) {
        KbDocument doc = requireDoc(docId);
        doc.setStatus(KbDocument.PARSING);
        docRepo.save(doc);
        return parser.parse(filename, bytes);
    }

    /**
     * 步骤 2 富解析（子项目 B）：置 PARSING，按介质类型分流——
     * PDF 逐页文本/OCR、图片 OCR、录音 ASR；加密异常由 handler 建 DECRYPT 人工任务。
     * 返回 ParsedDocument 由 handler 持久化为 _parsed.json（含分页/坐标/版面/表格证据）。
     */
    public ParsedDocument richParseStep(String docId, String filename, byte[] bytes, String password) {
        KbDocument doc = requireDoc(docId);
        doc.setStatus(KbDocument.PARSING);
        docRepo.save(doc);
        ParsedDocument parsed = richParser.parse(docId, filename, bytes, password);
        // C2：血缘链头 RAW_FILE → PARSED_TEXT；OCR 页/冲突页逐页留痕（均幂等）
        int ver = currentVersionNo(docId);
        DocArtifact raw = artifactStore.saveBytes(docId, ver, ArtifactType.RAW_FILE, null, bytes, filename);
        DocArtifact parsedArt = artifactStore.saveText(
                docId, ver, ArtifactType.PARSED_TEXT, null, parsed.fullText());
        provenance.addEdge(docId, ver, String.valueOf(raw.id()), "ARTIFACT",
                String.valueOf(parsedArt.id()), "ARTIFACT", EdgeType.DERIVED, "文档解析");
        for (var page : parsed.pages()) {
            if (page.ocr() != null) {
                DocArtifact ocrArt = artifactStore.saveText(docId, ver, ArtifactType.OCR_PAGE,
                        page.pageNo(), page.text() == null ? "" : page.text());
                provenance.addEdge(docId, ver, String.valueOf(raw.id()), "ARTIFACT",
                        String.valueOf(ocrArt.id()), "ARTIFACT", EdgeType.DERIVED,
                        "OCR 识别 pageNo=" + page.pageNo());
            }
            if (page.conflict()) {
                String json = conflictJson(page);
                DocArtifact conflictArt = artifactStore.saveBytes(docId, ver, ArtifactType.CONFLICT,
                        page.pageNo(), json.getBytes(StandardCharsets.UTF_8), "conflict.json");
                provenance.addEdge(docId, ver, String.valueOf(raw.id()), "ARTIFACT",
                        String.valueOf(conflictArt.id()), "ARTIFACT", EdgeType.DERIVED,
                        "文本层/OCR 冲突 pageNo=" + page.pageNo() + " diffRate=" + page.diffRate());
            }
        }
        return parsed;
    }

    private String conflictJson(com.wikiagent.domain.parse.model.ParsedPage page) {
        try {
            var node = objectMapper.createObjectNode()
                    .put("pageNo", page.pageNo())
                    .put("diffRate", page.diffRate());
            node.put("textLayerText", page.textLayerText());
            node.put("chosenText", page.text());
            if (page.ocr() != null) {
                node.put("ocrText", page.ocr().fullText())
                        .put("ocrConfidence", page.ocr().confidence());
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("冲突产物序列化失败: " + e.getMessage(), e);
        }
    }

    /** AI 依赖介质（扫描件/图片/录音）供应商未配置：置 AI_SKIPPED 终态，不进入切分。 */
    public void aiSkipStep(String docId) {
        KbDocument doc = requireDoc(docId);
        doc.setStatus(KbDocument.AI_SKIPPED);
        docRepo.save(doc);
        log.info("文档 AI 跳过终态 docId={} filename={}", docId, doc.getFilename());
    }

    /**
     * 步骤 100 版面/表格结构（仅 PDF）：读 _parsed.json → 版面分析 + 跨页表格拼接 → 回写。
     * 能力缺失时跳过增强，原样返回。
     */
    public ParsedDocument structureStep(String docId, byte[] bytes, String password) {
        ParsedDocument parsed = readParsed(docId);
        ParsedDocument enhanced = structureService.analyze(docId, parsed, bytes, password);
        writeParsed(docId, enhanced);
        // C2：拼接表落 STITCHED_TABLE 产物 + STITCHED 边（幂等：同首页覆盖、边去重）
        int ver = currentVersionNo(docId);
        DocArtifact parsedArt = findArtifact(docId, ver, ArtifactType.PARSED_TEXT, null);
        for (var table : enhanced.tables()) {
            if (table.pageNos().isEmpty()) {
                continue;
            }
            try {
                byte[] json = objectMapper.writeValueAsBytes(table);
                int firstPage = table.pageNos().get(0);
                DocArtifact tableArt = artifactStore.saveBytes(docId, ver,
                        ArtifactType.STITCHED_TABLE, firstPage, json, "table.json");
                if (parsedArt != null) {
                    provenance.addEdge(docId, ver, String.valueOf(parsedArt.id()), "ARTIFACT",
                            String.valueOf(tableArt.id()), "ARTIFACT", EdgeType.STITCHED,
                            "跨页表格 pages=" + table.pageNos()
                                    + " rows=" + table.rows() + " ambiguous=" + table.ambiguous());
                }
            } catch (Exception e) {
                throw new IllegalStateException("拼接表产物落库失败: " + e.getMessage(), e);
            }
        }
        return enhanced;
    }

    /**
     * 步骤 110 结构化字段抽取（子项目 B4）：置 EXTRACTING，按 schema 调 LLM 抽取并校验。
     * 低置信/校验失败 → report.needsReview()，handler 据此建 REVIEW 人工任务。
     */
    public ExtractionReport extractStep(String docId, String schemaKey) {
        KbDocument doc = requireDoc(docId);
        doc.setStatus(KbDocument.EXTRACTING);
        docRepo.save(doc);
        ParsedDocument parsed = readParsed(docId);
        ExtractionReport report = extractionService.extract(docId, schemaKey, parsed);
        writeExtraction(docId, report);
        // C2：字段落库 + PARSED_TEXT→FIELD 的 EXTRACTED 血缘边（可回溯文件+原文片段）
        int ver = currentVersionNo(docId);
        DocArtifact parsedArt = findArtifact(docId, ver, ArtifactType.PARSED_TEXT, null);
        for (ExtractedFieldValue f : report.fields()) {
            provenance.upsertField(docId, ver, f, report.schemaKey(), report.schemaVersion(),
                    f.key(), false);
            Integer pageNo = f.evidence().stream().map(com.wikiagent.domain.extract.FieldEvidence::pageNo)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            String snippet = f.evidence().stream().map(com.wikiagent.domain.extract.FieldEvidence::snippet)
                    .filter(s -> s != null && !s.isBlank()).findFirst().orElse(null);
            // 优先精确到页产物（OCR_PAGE），否则挂整文 PARSED_TEXT；note 带 pageNo+snippet
            DocArtifact source = pageNo == null ? null
                    : findArtifact(docId, ver, ArtifactType.OCR_PAGE, pageNo);
            if (source == null) {
                source = parsedArt;
            }
            if (source != null) {
                provenance.addEdge(docId, ver, String.valueOf(source.id()), "ARTIFACT",
                        f.key(), "FIELD", EdgeType.EXTRACTED,
                        (pageNo == null ? "" : "pageNo=" + pageNo + " ") + (snippet == null ? "" : snippet));
            }
        }
        return report;
    }

    private ParsedDocument readParsed(String docId) {
        try {
            return objectMapper.readValue(parsedJsonPath(docId).toFile(), ParsedDocument.class);
        } catch (Exception e) {
            throw new IllegalStateException("读取富解析结果失败 docId=" + docId + ": " + e.getMessage(), e);
        }
    }

    private void writeParsed(String docId, ParsedDocument parsed) {
        try {
            objectMapper.writeValue(parsedJsonPath(docId).toFile(), parsed);
        } catch (Exception e) {
            throw new IllegalStateException("写富解析结果失败 docId=" + docId + ": " + e.getMessage(), e);
        }
    }

    private void writeExtraction(String docId, ExtractionReport report) {
        try {
            objectMapper.writeValue(extractionPath(docId).toFile(), report);
        } catch (Exception e) {
            throw new IllegalStateException("写抽取结果失败 docId=" + docId + ": " + e.getMessage(), e);
        }
    }

    private static Path parsedJsonPath(String docId) {
        return Path.of("data", "uploads", docId, "_parsed.json");
    }

    private static Path extractionPath(String docId) {
        return Path.of("data", "uploads", docId, "_extracted.json");
    }

    /** 当前文档版本号：无版本时建 v1（首次解析）。 */
    private int currentVersionNo(String docId) {
        DocVersion latest = provenance.latestVersion(docId);
        if (latest == null) {
            return provenance.createDocVersion(docId, null, "首次解析", null, "system").versionNo();
        }
        return latest.versionNo();
    }

    private DocArtifact findArtifact(String docId, int versionNo, ArtifactType type, Integer pageNo) {
        return artifactStore.find(docId, versionNo, type, pageNo);
    }

    /** 步骤 3 清洗：置 CLEANING；C2 写 CLEANED_TEXT 产物 + PARSED→CLEANED DERIVED 边。 */
    public String cleanStep(String docId, String raw) {
        KbDocument doc = requireDoc(docId);
        doc.setStatus(KbDocument.CLEANING);
        docRepo.save(doc);
        String cleaned = cleaner.clean(raw);
        int ver = currentVersionNo(docId);
        DocArtifact parsed = findArtifact(docId, ver, ArtifactType.PARSED_TEXT, null);
        DocArtifact cleanedArt = artifactStore.saveText(docId, ver, ArtifactType.CLEANED_TEXT, null, cleaned);
        if (parsed != null) {
            provenance.addEdge(docId, ver, String.valueOf(parsed.id()), "ARTIFACT",
                    String.valueOf(cleanedArt.id()), "ARTIFACT", EdgeType.DERIVED, "文本清洗");
        }
        return cleaned;
    }

    /**
     * 修复4：重解析入口——基于当前 PUBLISHED 版本开新版本（旧版 SUPERSEDED，SUPERSEDES 链），
     * 并软下线旧子块。新版本的产物/字段/边由后续 richParseStep…extractStep 按新版本号写入。
     */
    @Transactional
    public int beginReparse(String docId, String changeSummary, String createdBy) {
        requireDoc(docId);
        DocVersion latest = provenance.latestVersion(docId);
        Integer parent = latest == null ? null : latest.versionNo();
        int newVer = provenance.createDocVersion(docId, parent,
                changeSummary == null ? "重解析" : changeSummary, null,
                createdBy == null ? "system" : createdBy).versionNo();
        childRepo.deactivateByDocId(docId);
        return newVer;
    }

    /**
     * 步骤 4 父/子切分：置 CHUNKING；幂等（当前版本 active 子块已存在则跳过，断点重跑行数不翻倍）；
     * 重解析场景旧版本子块软下线保留；v4 §6.6.1 知识打标（失败不阻断入库主流程，仅告警）。
     * C2/修复4：子块带 version_no + 来源页码（由 _parsed.json 分页粗映射）。
     */
    @Transactional
    public IngestOutcome splitStep(String docId, String cleaned, KnowledgeTagContext tagContext) {
        KbDocument doc = requireDoc(docId);
        int ver = currentVersionNo(docId);
        if (!childRepo.findByDocIdAndActiveTrue(docId).isEmpty()) {
            return new IngestOutcome(doc.getParentCount(), (int) childRepo.findByDocIdAndActiveTrue(docId).size());
        }
        doc.setStatus(KbDocument.CHUNKING);
        docRepo.save(doc);
        ChunkSplitter splitter = new ChunkSplitter(
                props.ingest().parentChars(), props.ingest().parentOverlap(),
                props.ingest().childChars(), props.ingest().childOverlap());
        List<String> parents = splitter.splitParents(cleaned);
        if (parents.isEmpty()) {
            throw new IllegalStateException("文档清洗切分后为空，请检查文档内容");
        }

        childRepo.deactivateByDocId(docId);
        parentRepo.deleteByDocId(docId);
        List<ParsedPage> pages = readParsedPages(docId);
        List<KbParentChunk> parentEntities = new ArrayList<>(parents.size());
        List<KbChildChunk> childEntities = new ArrayList<>();
        for (int p = 0; p < parents.size(); p++) {
            String parentId = UUID.randomUUID().toString();
            parentEntities.add(newParent(parentId, docId, p, parents.get(p)));
            List<String> children = splitter.splitChildren(parents.get(p));
            for (int c = 0; c < children.size(); c++) {
                String text = children.get(c);
                Integer pageNo = mapToPageNo(text, pages);
                KbChildChunk child = newChild(UUID.randomUUID().toString(), docId, parentId, c,
                        text, pageNo, ver);
                // L0 精确去重：规范化 hash 落库（开关关闭也写 hash，便于后续追溯）
                child.setContentHash(ContentHasher.sha256Normalized(text));
                childEntities.add(child);
            }
        }
        parentRepo.saveAll(parentEntities);
        childRepo.saveAll(childEntities);
        doc.setParentCount(parentEntities.size());
        doc.setChildCount(childEntities.size());
        docRepo.save(doc);

        // L0 精确去重：本批 chunk 命中已有 active chunk 时按生效日期裁决谁留 active
        if (dedupEnabled && metricEventDao != null) {
            applyExactDedup(doc, childEntities);
        }

        try {
            taggingService.tagDocument(docId, childEntities, tagContext);
        } catch (Exception tagEx) {
            log.warn("知识元数据打标失败 docId={}: {}", docId, tagEx.getMessage());
        }
        return new IngestOutcome(parentEntities.size(), childEntities.size());
    }

    /**
     * L0 精确去重裁决（splitStep 同事务内）：
     * 对本批 chunk 逐条按 content_hash 查已有 active chunk（排除本 docId）；
     * 命中时——生效日晚者留 active，早者置 false；相同/缺失则留旧（已存在者）。
     * 命中即写 DEDUP_EXACT_SKIPPED metric_event，chunkId 用真实新 chunk id。
     * 软删 only：MySQL 是真相源，无 Milvus chunk 级删除。
     */
    private void applyExactDedup(KbDocument newDoc, List<KbChildChunk> newChunks) {
        for (KbChildChunk fresh : newChunks) {
            String hash = fresh.getContentHash();
            if (hash == null || hash.isEmpty()) {
                continue;
            }
            KbChildChunk existing = childRepo.findFirstByContentHashAndActiveTrue(hash).orElse(null);
            if (existing == null || existing.getDocId().equals(newDoc.getId())) {
                continue;
            }
            KbDocument existingDoc = docRepo.findById(existing.getDocId()).orElse(null);
            java.time.LocalDate newDate = newDoc.getEffectiveDate();
            java.time.LocalDate oldDate = existingDoc == null ? null : existingDoc.getEffectiveDate();
            // 规则：晚者胜；任一缺失或相同 → 留旧（existing 保持 active，新块置 false）
            boolean newWins = newDate != null && oldDate != null && newDate.isAfter(oldDate);
            if (newWins) {
                existing.setActive(false);
                childRepo.save(existing);
                // fresh 保持 active=true（默认）；落 metric
                log.info("L0 精确去重：新块生效日晚于旧块，旧块下线 existingChunk={} newChunk={} hash={}",
                        existing.getId(), fresh.getId(), hash);
            } else {
                fresh.setActive(false);
                childRepo.save(fresh);
                log.info("L0 精确去重：新块生效日不晚于旧块，新块下线 existingChunk={} newChunk={} hash={}",
                        existing.getId(), fresh.getId(), hash);
            }
            MetricEventEntity metric = new MetricEventEntity();
            metric.setChunkId(fresh.getId());
            metric.setEventType("DEDUP_EXACT_SKIPPED");
            metricEventDao.save(metric);
        }
    }

    /** 读富解析分页（旧解析路径无 _parsed.json → 空列表，页码留 null）。 */
    private List<ParsedPage> readParsedPages(String docId) {
        try {
            ParsedDocument parsed = objectMapper.readValue(parsedJsonPath(docId).toFile(), ParsedDocument.class);
            return parsed.pages() == null ? List.of() : parsed.pages();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 子块→来源页码粗映射：取子块前 20 个非空白字符，按文档顺序在页文本中定位；
     * 清洗只去空白不改字序，故归一化后包含匹配。找不到（跨页/纯文本无分页）返回 null。
     */
    private Integer mapToPageNo(String childText, List<ParsedPage> pages) {
        if (pages.isEmpty()) {
            return null;
        }
        String key = squash(childText);
        if (key.length() < 6) {
            return null;
        }
        key = key.substring(0, Math.min(20, key.length()));
        for (ParsedPage page : pages) {
            if (page.text() != null && squash(page.text()).contains(key)) {
                return page.pageNo();
            }
        }
        return null;
    }

    private String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /**
     * 步骤 5 向量化 + 写索引（DashScope 单次批量上限 10）。
     * 幂等：doc 已 READY（重复调度）返回 false 跳过，不重复写 Milvus。
     * <p>
     * L1 近重复守卫在 embedding 之前执行（命中即省输家 chunk 的 embedding 费用）；
     * is_active 交换与本方法其余 chunk 状态修改处于同一事务（与 splitStep 的 @Transactional 语义一致）。
     */
    @Transactional
    public boolean embedAndPersistStep(String docId) {
        KbDocument doc = requireDoc(docId);
        if (KbDocument.READY.equals(doc.getStatus())) {
            return false;
        }
        // L1 近重复守卫：检测/Redis 异常降级跳过并告警，不阻断入库主流程
        if (nearDuplicateGuard != null) {
            try {
                nearDuplicateGuard.inspect(docId);
            } catch (Exception e) {
                log.warn("L1 近重复检测失败（降级跳过，不阻断入库）docId={}: {}", docId, e.getMessage());
            }
        }
        doc.setStatus(KbDocument.EMBEDDING);
        docRepo.save(doc);
        List<KbChildChunk> childEntities = childRepo.findByDocIdAndActiveTrue(docId);
        int batch = Math.max(1, props.ingest().embeddingBatch());
        List<float[]> allVectors = new ArrayList<>(childEntities.size());
        for (int i = 0; i < childEntities.size(); i += batch) {
            List<String> texts = childEntities.subList(i, Math.min(i + batch, childEntities.size()))
                    .stream().map(KbChildChunk::getContent).toList();
            // 缺陷14：先查 embedding 精确缓存（未命中批量回源并回填）；缓存服务缺席时直调模型
            List<float[]> vectors = embeddingCache == null
                    ? embeddingModel.embed(texts)
                    : embeddingCache.embedAll(embeddingModelName, texts, embeddingModel::embed);
            allVectors.addAll(vectors);
        }

        doc.setStatus(KbDocument.INDEXING);
        docRepo.save(doc);
        milvus.insertChildren(childEntities, allVectors);
        return true;
    }

    /** 步骤 6 索引校验 + READY 终态。 */
    public void finalizeStep(String docId) {
        KbDocument doc = requireDoc(docId);
        List<KbChildChunk> children = childRepo.findByDocIdAndActiveTrue(docId);
        if (children.isEmpty()) {
            throw new IllegalStateException("索引校验失败：无有效子块 docId=" + docId);
        }
        doc.setStatus(KbDocument.READY);
        docRepo.save(doc);
        log.info("文档入库完成: docId={} filename={} children={}", docId, doc.getFilename(), children.size());
        // GraphRAG：异步提取实体关系（不阻塞主流程）
        if (graphExtractionService != null) {
            try {
                graphExtractionService.extractFromDocument(docId, children);
            } catch (Exception e) {
                log.warn("GraphRAG 提取失败 docId={}: {}", docId, e.getMessage());
            }
        }
    }

    /**
     * 任务终态失败钩子（TaskHandler.onFailed → IngestTaskHandler）：把文档置 FAILED 并记录错误。
     * 不覆盖 READY/AI_SKIPPED 终态（任务已正常收尾后不允许回滚）。
     */
    public void markFailedStep(String docId, String errorMsg) {
        KbDocument doc = docRepo.findById(docId).orElse(null);
        if (doc == null) {
            return;
        }
        if (KbDocument.READY.equals(doc.getStatus()) || KbDocument.AI_SKIPPED.equals(doc.getStatus())) {
            return;
        }
        doc.setStatus(KbDocument.FAILED);
        doc.setError(errorMsg == null ? "任务失败" : errorMsg);
        docRepo.save(doc);
        log.warn("文档入库失败（任务终态）: docId={} filename={} error={}", docId, doc.getFilename(), errorMsg);
    }

    /** 全流水线（同步、异常上抛由调用方分类）：任务 handler 与旧异步入口共用。 */
    public IngestOutcome runPipeline(String docId, String filename, byte[] bytes, KnowledgeTagContext tagContext) {
        String raw = parseStep(docId, filename, bytes);
        String cleaned = cleanStep(docId, raw);
        IngestOutcome outcome = splitStep(docId, cleaned, tagContext);
        embedAndPersistStep(docId);
        finalizeStep(docId);
        return outcome;
    }

    // ===== 旧异步入口（enabled=false / 兼容路径）=====

    /** 兼容入口：使用保守默认标签（v4 §6.6.1）。 */
    @Async("ingestExecutor")
    public void ingest(String docId, String filename, byte[] bytes) {
        ingest(docId, filename, bytes, KnowledgeTagContext.defaultFor(filename));
    }

    /**
     * 入库流水线（异步）：runPipeline + 异常兜底置 FAILED（维持既有行为）。
     *
     * @param tagContext v4 知识打标上下文（9×6 领域标签 + 创建者元数据）
     */
    @Async("ingestExecutor")
    public void ingest(String docId, String filename, byte[] bytes, KnowledgeTagContext tagContext) {
        KbDocument doc = docRepo.findById(docId).orElse(null);
        if (doc == null) {
            return;
        }
        try {
            runPipeline(docId, filename, bytes, tagContext);
        } catch (Exception e) {
            log.error("文档入库失败: {}", filename, e);
            doc.setStatus(KbDocument.FAILED);
            doc.setError(summarize(e));
            docRepo.save(doc);
        }
    }

    /** 删除文档：先删 Milvus（失败则整体失败保持一致），再删本地。 */
    @Transactional
    public void delete(String docId) {
        milvus.deleteByDocId(docId);
        childRepo.deleteByDocId(docId);
        parentRepo.deleteByDocId(docId);
        docRepo.deleteById(docId);
        // GraphRAG：软删除关联实体和关系
        if (graphExtractionService != null) {
            try {
                graphExtractionService.deactivateByDocId(docId);
            } catch (Exception e) {
                log.warn("GraphRAG 删除失败 docId={}: {}", docId, e.getMessage());
            }
        }
        try {
            Path dir = Path.of("data", "uploads", docId);
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        } catch (Exception e) {
            log.warn("上传文件清理失败: {}", e.getMessage());
        }
    }

    private KbDocument requireDoc(String docId) {
        return docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId));
    }

    private static String summarize(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 1500 ? msg.substring(0, 1500) : msg;
    }

    private KbParentChunk newParent(String id, String docId, int idx, String content) {
        KbParentChunk p = new KbParentChunk();
        p.setId(id);
        p.setDocId(docId);
        p.setParentIndex(idx);
        p.setContent(content);
        return p;
    }

    private KbChildChunk newChild(String id, String docId, String parentId, int idx,
                                  String content, Integer pageNo, int versionNo) {
        KbChildChunk c = new KbChildChunk();
        c.setId(id);
        c.setDocId(docId);
        c.setParentId(parentId);
        c.setChildIndex(idx);
        c.setContent(content);
        c.setPageNo(pageNo);
        c.setVersionNo(versionNo);
        c.setActive(true);
        return c;
    }
}
