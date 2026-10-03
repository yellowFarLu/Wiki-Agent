package com.wikiagent.application.knowledge;

import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.infrastructure.persistence.ConflictResolutionEntity;
import com.wikiagent.infrastructure.persistence.ConflictResolutionJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 9 离线扫描分流：定时扫描命中相似对时按关键 token 差异分流——
 * <ul>
 *   <li>关键 token 差异（数字/日期/货币/否定词）→ resolution_hint=CONFLICT</li>
 *   <li>仅虚词差异 → resolution_hint=REDUNDANT（建议合并而非冲突裁决）</li>
 *   <li>source 一律 SCHEDULED_SCAN（与 ONLINE_GUARD / MINHASH_INGEST 区分来源）</li>
 * </ul>
 */
class ConflictDetectionServiceTest {

    private final KnowledgeMetadataJpaDao metadataDao = mock(KnowledgeMetadataJpaDao.class);
    private final KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
    private final ConflictResolutionJpaDao conflictDao = mock(ConflictResolutionJpaDao.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

    private ConflictDetectionService service() {
        return new ConflictDetectionService(metadataDao, childRepo, conflictDao, embeddingModel, 0.8);
    }

    private static KnowledgeMetadataEntity meta(String chunkId) {
        KnowledgeMetadataEntity m = new KnowledgeMetadataEntity();
        m.setChunkId(chunkId);
        m.setDocId("doc-" + chunkId);
        m.setDomainTag("水果");
        m.setSubDomainTag("苹果");
        m.setRequiredIdentity("all");
        return m;
    }

    private static KbChildChunk chunk(String id, String content) {
        KbChildChunk c = new KbChildChunk();
        c.setId(id);
        c.setContent(content);
        return c;
    }

    /** 两 chunk 向量相同（余弦 1.0 > 阈值 0.8），非零向量避开 NoOp 跳过逻辑。 */
    private void stubSimilarVectors() {
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[]{1f, 0f}).toList();
        });
    }

    private ConflictResolutionEntity savedConflict() {
        ArgumentCaptor<ConflictResolutionEntity> captor =
                ArgumentCaptor.forClass(ConflictResolutionEntity.class);
        verify(conflictDao).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void 扫描命中相似对_关键token差异_提示CONFLICT且来源为定时扫描() {
        when(metadataDao.findAllActive()).thenReturn(List.of(meta("c-a"), meta("c-b")));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(
                chunk("c-a", "苹果售价5元"), chunk("c-b", "苹果售价3元")));
        when(conflictDao.findAll()).thenReturn(List.of());
        stubSimilarVectors();

        int found = service().scan();

        assertThat(found).isEqualTo(1);
        ConflictResolutionEntity e = savedConflict();
        assertThat(e.getStatus()).isEqualTo("DETECTED");
        assertThat(e.getSource()).isEqualTo("SCHEDULED_SCAN");
        assertThat(e.getResolutionHint()).isEqualTo("CONFLICT");
        assertThat(e.getSimilarity()).isGreaterThan(0.8);
        assertThat(e.getDomainTag()).isEqualTo("水果");
    }

    @Test
    void 扫描命中相似对_仅虚词差异_提示REDUNDANT且来源为定时扫描() {
        when(metadataDao.findAllActive()).thenReturn(List.of(meta("c-a"), meta("c-b")));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(
                chunk("c-a", "请及时处理售后问题"), chunk("c-b", "请尽快处理售后问题")));
        when(conflictDao.findAll()).thenReturn(List.of());
        stubSimilarVectors();

        int found = service().scan();

        assertThat(found).isEqualTo(1);
        ConflictResolutionEntity e = savedConflict();
        assertThat(e.getStatus()).isEqualTo("DETECTED");
        assertThat(e.getSource()).isEqualTo("SCHEDULED_SCAN");
        assertThat(e.getResolutionHint()).isEqualTo("REDUNDANT");
    }

    @Test
    void 零向量时跳过检测不写冲突单() {
        // 无 DASHSCOPE_API_KEY 时 EmbeddingModel 为 NoOp（全 0 向量）→ 余弦无意义，跳过
        when(metadataDao.findAllActive()).thenReturn(List.of(meta("c-a"), meta("c-b")));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(
                chunk("c-a", "苹果售价5元"), chunk("c-b", "苹果售价3元")));
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[]{0f, 0f}).toList();
        });

        int found = service().scan();

        assertThat(found).isEqualTo(0);
        verify(conflictDao, never()).save(any(ConflictResolutionEntity.class));
    }
}
