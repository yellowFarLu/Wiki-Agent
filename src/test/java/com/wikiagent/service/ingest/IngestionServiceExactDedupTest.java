package com.wikiagent.service.ingest;

import com.wikiagent.application.extract.FieldExtractionService;
import com.wikiagent.application.knowledge.KnowledgeTagContext;
import com.wikiagent.application.knowledge.KnowledgeTaggingService;
import com.wikiagent.application.lineage.ProvenanceService;
import com.wikiagent.application.parse.PageStructureService;
import com.wikiagent.application.parse.RichDocumentParser;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.lineage.DocVersion;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.infrastructure.lineage.ArtifactStore;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.store.MilvusStoreService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L0 入库精确去重：splitStep 内规范化 hash 命中已有 active chunk 时——
 * 生效时间晚者留 active，早者置 false；相同/缺失则留旧（已存在者）。
 * 命中即写 DEDUP_EXACT_SKIPPED metric_event（chunkId 用真实新 chunk id）。
 */
class IngestionServiceExactDedupTest {

    private IngestionService newService(KbDocumentRepo docRepo, KbChildChunkRepo childRepo,
                                        KbParentChunkRepo parentRepo,
                                        MetricEventJpaDao metricEventDao) {
        return new IngestionService(
                new WikiAgentProperties(null, null,
                        new WikiAgentProperties.Ingest(800, 100, 350, 60, 10), null),
                mock(DocumentParser.class), mock(TextCleaner.class),
                mock(RichDocumentParser.class), mock(PageStructureService.class),
                mock(FieldExtractionService.class), mock(ProvenanceService.class),
                mock(ArtifactStore.class), docRepo, parentRepo, childRepo,
                mock(MilvusStoreService.class), mock(EmbeddingModel.class),
                mock(KnowledgeTaggingService.class),
                /* dedupEnabled */ true, metricEventDao);
    }

    private KbDocument docOf(String id, LocalDate effectiveDate) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setFilename(id + ".txt");
        d.setDocType("txt");
        d.setStatus(KbDocument.CLEANING);
        d.setEffectiveDate(effectiveDate);
        return d;
    }

    private KbChildChunk activeChunkOf(String id, String docId, String content, int versionNo) {
        KbChildChunk c = new KbChildChunk();
        c.setId(id);
        c.setDocId(docId);
        c.setParentId("p-" + docId);
        c.setChildIndex(0);
        c.setContent(content);
        c.setVersionNo(versionNo);
        c.setActive(true);
        c.setContentHash(ContentHasher.sha256Normalized(content));
        return c;
    }

    @Test
    void 已有active块且新文档生效日晚时旧块置false新块保持true() {
        // A：已入库，effective_date=2024-01-01
        KbDocument docA = docOf("docA", LocalDate.of(2024, 1, 1));
        // B：新入库，effective_date=2025-01-01（晚）
        KbDocument docB = docOf("docB", LocalDate.of(2025, 1, 1));

        // 规范化后相同的 chunk：B 含空白 / 大写，A 是已存在的 active
        String aText = "苹果是5元";
        String bText = "苹果 是 5 元";
        KbChildChunk existing = activeChunkOf("chunkA-1", "docA", aText, 1);

        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        MetricEventJpaDao metricDao = mock(MetricEventJpaDao.class);
        ProvenanceService provenance = mock(ProvenanceService.class);

        when(docRepo.findById("docB")).thenReturn(Optional.of(docB));
        when(docRepo.findById("docA")).thenReturn(Optional.of(docA));
        when(docRepo.save(any(KbDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(provenance.latestVersion(anyString())).thenReturn(null);
        when(provenance.createDocVersion(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(new DocVersion(1L, "docB", 1, DocVersion.PUBLISHED,
                        null, "首次解析", null, "system", java.time.Instant.now()));

        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of());
        when(childRepo.findByDocIdAndActiveTrue("docA")).thenReturn(List.of(existing));
        when(childRepo.findFirstByContentHashAndActiveTrue(existing.getContentHash()))
                .thenReturn(Optional.of(existing));

        IngestionService svc = new IngestionService(
                new WikiAgentProperties(null, null,
                        new WikiAgentProperties.Ingest(800, 100, 350, 60, 10), null),
                mock(DocumentParser.class), mock(TextCleaner.class),
                mock(RichDocumentParser.class), mock(PageStructureService.class),
                mock(FieldExtractionService.class), provenance,
                mock(ArtifactStore.class), docRepo, parentRepo, childRepo,
                mock(MilvusStoreService.class), mock(EmbeddingModel.class),
                mock(KnowledgeTaggingService.class),
                /* dedupEnabled */ true, metricDao);

        svc.splitStep("docB", bText, KnowledgeTagContext.defaultFor("docB.txt"));

        // 旧 chunk 置 false；B 的新 chunk 保持 active=true
        assertFalse(existing.isActive(), "B 晚于 A：A 的旧 chunk 应被置为 inactive");
        ArgumentCaptor<KbChildChunk> cap = ArgumentCaptor.forClass(KbChildChunk.class);
        verify(childRepo, atLeastOnce()).saveAll(argThat(list -> {
            @SuppressWarnings("unchecked")
            List<KbChildChunk> chunks = (List<KbChildChunk>) list;
            return chunks.stream().allMatch(KbChildChunk::isActive)
                    && chunks.stream().allMatch(c -> c.getContentHash() != null);
        }));
        // metric_event 写入 DEDUP_EXACT_SKIPPED
        ArgumentCaptor<MetricEventEntity> mCap = ArgumentCaptor.forClass(MetricEventEntity.class);
        verify(metricDao, atLeastOnce()).save(mCap.capture());
        assertEquals("DEDUP_EXACT_SKIPPED", mCap.getValue().getEventType());
    }

    @Test
    void 已有active块且新文档生效日早时新块置false旧块保持true() {
        // A：已入库，effective_date=2025-01-01
        KbDocument docA = docOf("docA", LocalDate.of(2025, 1, 1));
        // B：新入库，effective_date=2024-01-01（早）
        KbDocument docB = docOf("docB", LocalDate.of(2024, 1, 1));

        String aText = "苹果是5元";
        String bText = "苹果 是 5 元";
        KbChildChunk existing = activeChunkOf("chunkA-1", "docA", aText, 1);

        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        MetricEventJpaDao metricDao = mock(MetricEventJpaDao.class);
        ProvenanceService provenance = mock(ProvenanceService.class);

        when(docRepo.findById("docB")).thenReturn(Optional.of(docB));
        when(docRepo.findById("docA")).thenReturn(Optional.of(docA));
        when(docRepo.save(any(KbDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(provenance.latestVersion(anyString())).thenReturn(null);
        when(provenance.createDocVersion(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(new DocVersion(1L, "docB", 1, DocVersion.PUBLISHED,
                        null, "首次解析", null, "system", java.time.Instant.now()));
        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of());
        when(childRepo.findFirstByContentHashAndActiveTrue(existing.getContentHash()))
                .thenReturn(Optional.of(existing));

        IngestionService svc = new IngestionService(
                new WikiAgentProperties(null, null,
                        new WikiAgentProperties.Ingest(800, 100, 350, 60, 10), null),
                mock(DocumentParser.class), mock(TextCleaner.class),
                mock(RichDocumentParser.class), mock(PageStructureService.class),
                mock(FieldExtractionService.class), provenance,
                mock(ArtifactStore.class), docRepo, parentRepo, childRepo,
                mock(MilvusStoreService.class), mock(EmbeddingModel.class),
                mock(KnowledgeTaggingService.class),
                /* dedupEnabled */ true, metricDao);

        svc.splitStep("docB", bText, KnowledgeTagContext.defaultFor("docB.txt"));

        // 旧块保持 active；B 新块置 false
        assertTrue(existing.isActive(), "B 早于 A：A 的旧 chunk 应保持 active");
        ArgumentCaptor<List<KbChildChunk>> cap = ArgumentCaptor.forClass(List.class);
        verify(childRepo, atLeastOnce()).saveAll(cap.capture());
        List<KbChildChunk> newChunks = cap.getAllValues().stream()
                .flatMap(List::stream).filter(c -> "docB".equals(c.getDocId())).toList();
        assertFalse(newChunks.isEmpty(), "B 应已落库");
        assertTrue(newChunks.stream().allMatch(c -> !c.isActive()),
                "B 的新 chunk 应被置为 inactive");
        verify(metricDao, atLeastOnce()).save(argThat(e ->
                "DEDUP_EXACT_SKIPPED".equals(e.getEventType())));
    }

    @Test
    void 缺失生效日期时保留旧块新块置false() {
        KbDocument docA = docOf("docA", null);  // 旧文档无 effective_date
        KbDocument docB = docOf("docB", null);  // 新文档也无

        String aText = "苹果是5元";
        String bText = "苹果 是 5 元";
        KbChildChunk existing = activeChunkOf("chunkA-1", "docA", aText, 1);

        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        MetricEventJpaDao metricDao = mock(MetricEventJpaDao.class);
        ProvenanceService provenance = mock(ProvenanceService.class);

        when(docRepo.findById("docB")).thenReturn(Optional.of(docB));
        when(docRepo.findById("docA")).thenReturn(Optional.of(docA));
        when(docRepo.save(any(KbDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(provenance.latestVersion(anyString())).thenReturn(null);
        when(provenance.createDocVersion(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(new DocVersion(1L, "docB", 1, DocVersion.PUBLISHED,
                        null, "首次解析", null, "system", java.time.Instant.now()));
        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of());
        when(childRepo.findFirstByContentHashAndActiveTrue(existing.getContentHash()))
                .thenReturn(Optional.of(existing));

        IngestionService svc = new IngestionService(
                new WikiAgentProperties(null, null,
                        new WikiAgentProperties.Ingest(800, 100, 350, 60, 10), null),
                mock(DocumentParser.class), mock(TextCleaner.class),
                mock(RichDocumentParser.class), mock(PageStructureService.class),
                mock(FieldExtractionService.class), provenance,
                mock(ArtifactStore.class), docRepo, parentRepo, childRepo,
                mock(MilvusStoreService.class), mock(EmbeddingModel.class),
                mock(KnowledgeTaggingService.class),
                /* dedupEnabled */ true, metricDao);

        svc.splitStep("docB", bText, KnowledgeTagContext.defaultFor("docB.txt"));

        assertTrue(existing.isActive(), "生效日缺失时应保留旧块");
        ArgumentCaptor<List<KbChildChunk>> cap = ArgumentCaptor.forClass(List.class);
        verify(childRepo, atLeastOnce()).saveAll(cap.capture());
        List<KbChildChunk> newChunks = cap.getAllValues().stream()
                .flatMap(List::stream).filter(c -> "docB".equals(c.getDocId())).toList();
        assertFalse(newChunks.isEmpty());
        assertTrue(newChunks.stream().allMatch(c -> !c.isActive()));
    }

    @Test
    void 去重开关关闭时跳过检测不写metric() {
        KbDocument docB = docOf("docB", null);
        String bText = "苹果 是 5 元";

        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        MetricEventJpaDao metricDao = mock(MetricEventJpaDao.class);
        ProvenanceService provenance = mock(ProvenanceService.class);

        when(docRepo.findById("docB")).thenReturn(Optional.of(docB));
        when(docRepo.save(any(KbDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(provenance.latestVersion(anyString())).thenReturn(null);
        when(provenance.createDocVersion(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(new DocVersion(1L, "docB", 1, DocVersion.PUBLISHED,
                        null, "首次解析", null, "system", java.time.Instant.now()));
        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of());

        IngestionService svc = new IngestionService(
                new WikiAgentProperties(null, null,
                        new WikiAgentProperties.Ingest(800, 100, 350, 60, 10), null),
                mock(DocumentParser.class), mock(TextCleaner.class),
                mock(RichDocumentParser.class), mock(PageStructureService.class),
                mock(FieldExtractionService.class), provenance,
                mock(ArtifactStore.class), docRepo, parentRepo, childRepo,
                mock(MilvusStoreService.class), mock(EmbeddingModel.class),
                mock(KnowledgeTaggingService.class),
                /* dedupEnabled */ false, metricDao);

        svc.splitStep("docB", bText, KnowledgeTagContext.defaultFor("docB.txt"));

        verify(childRepo, never()).findFirstByContentHashAndActiveTrue(anyString());
        verify(metricDao, never()).save(any());
    }
}
