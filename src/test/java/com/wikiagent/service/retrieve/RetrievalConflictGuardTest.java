package com.wikiagent.service.retrieve;

import com.wikiagent.application.gray.GrayReleaseService;
import com.wikiagent.application.ragcache.EmbeddingCacheService;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.infrastructure.persistence.ConflictResolutionEntity;
import com.wikiagent.infrastructure.persistence.ConflictResolutionJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.retrieve.ConflictCandidateDetector.RepChunkView;
import com.wikiagent.service.retrieve.ConflictLlmJudge.ConflictVerdict;
import com.wikiagent.service.retrieve.RetrievalConflictGuard.GuardResult;
import com.wikiagent.service.store.MilvusStoreService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 7 ConflictGuard 裁决与检索链路集成。
 * <p>
 * 裁决语义（LLM 只检测不裁决，系统按 kb_document.effective_date 确定性选边）：
 * <ul>
 *   <li>生效时间唯一最晚者胜，其余 chunk 移除（CONFLICT_GUARD_REMOVED）+ 写 DETECTED 冲突单
 *       （source=ONLINE_GUARD，pairKey 无序对已存在任意状态则不重复写）</li>
 *   <li>生效时间相同/一方缺失/组内并列最晚 → 双（全）保留 + conflictNote 标注
 *       （CONFLICT_GUARD_KEPT_BOTH），不默认选边（Review Focus #1/#4）</li>
 *   <li>开关关闭 / 灰度 conflict-guard 未命中 → 原样透传零副作用</li>
 *   <li>粗筛无疑似对 → 零 LLM 调用</li>
 * </ul>
 */
class RetrievalConflictGuardTest {

    private static final String TEXT_A = "苹果售价5元";
    private static final String TEXT_B = "苹果售价3元";
    private static final String TEXT_C = "苹果售价8元";

    private final KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
    private final MetricEventJpaDao metricDao = mock(MetricEventJpaDao.class);
    private final ConflictResolutionJpaDao conflictDao = mock(ConflictResolutionJpaDao.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final ConflictLlmJudge judge = mock(ConflictLlmJudge.class);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(bean);
        return op;
    }

    private KbDocument docOf(String id, LocalDate effectiveDate) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setFilename(id + ".txt");
        d.setDocType("txt");
        d.setEffectiveDate(effectiveDate);
        return d;
    }

    /** 组守卫：docRepo 按 id 回查文档，embeddingModel 按文本取向量（余弦由测试数据控制）。 */
    private RetrievalConflictGuard newGuard(boolean enabled, GrayReleaseService gray,
                                            List<KbDocument> docs, Map<String, float[]> vectors) {
        WikiAgentProperties props = new WikiAgentProperties(null, null, null, null, null,
                new WikiAgentProperties.ConflictGuard(enabled, 0.85));
        when(docRepo.findAllById(any())).thenAnswer(inv -> {
            Set<String> ids = new HashSet<>();
            inv.getArgument(0, Iterable.class).forEach(id -> ids.add((String) id));
            return docs.stream().filter(d -> ids.contains(d.getId())).toList();
        });
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(vectors::get).toList();
        });
        return new RetrievalConflictGuard(props, docRepo, metricDao, conflictDao, embeddingModel,
                providerOf((EmbeddingCacheService) null), providerOf(judge), providerOf(gray),
                "test-embedding");
    }

    private static RepChunkView view(String childId, String docId, String content) {
        return new RepChunkView(childId, docId, content, 0.9);
    }

    /** 高相似向量组：A/B/C 两两余弦 1.0（≥0.85），内容含关键 token 差异（5/3/8元）。 */
    private static Map<String, float[]> similarVectors() {
        return Map.of(TEXT_A, new float[]{1f, 0f}, TEXT_B, new float[]{1f, 0f},
                TEXT_C, new float[]{1f, 0f});
    }

    private List<MetricEventEntity> savedMetrics() {
        ArgumentCaptor<MetricEventEntity> captor = ArgumentCaptor.forClass(MetricEventEntity.class);
        verify(metricDao, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    private List<ConflictResolutionEntity> savedConflicts() {
        ArgumentCaptor<ConflictResolutionEntity> captor =
                ArgumentCaptor.forClass(ConflictResolutionEntity.class);
        verify(conflictDao, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void 生效时间晚者胜出_输家被移除并打点写冲突单() {
        List<KbDocument> docs = List.of(
                docOf("docA", LocalDate.of(2025, 1, 1)),
                docOf("docB", LocalDate.of(2026, 1, 1)));
        RetrievalConflictGuard guard = newGuard(true, null, docs, similarVectors());
        when(judge.judge(anyString(), anyList())).thenReturn(Optional.of(
                new ConflictVerdict("c-a", "c-b", "苹果", "售价", "5元", "3元")));
        when(conflictDao.findAll()).thenReturn(List.of());

        List<RepChunkView> candidates = List.of(
                view("c-a", "docA", TEXT_A), view("c-b", "docB", TEXT_B));
        GuardResult result = guard.apply("苹果多少钱", candidates);

        assertThat(result.kept()).extracting(RepChunkView::childId).containsExactly("c-b");
        assertThat(result.conflictNote()).isNull();
        assertThat(result.verdicts()).hasSize(1);

        List<MetricEventEntity> metrics = savedMetrics();
        assertThat(metrics).anyMatch(m -> "CONFLICT_GUARD_REMOVED".equals(m.getEventType())
                && "c-a".equals(m.getChunkId()));
        assertThat(metrics).anyMatch(m -> "CONFLICT_GUARD_DETECTED".equals(m.getEventType())
                && "c-a".equals(m.getChunkId()));
        assertThat(metrics).anyMatch(m -> "CONFLICT_GUARD_DETECTED".equals(m.getEventType())
                && "c-b".equals(m.getChunkId()));

        List<ConflictResolutionEntity> conflicts = savedConflicts();
        assertThat(conflicts).hasSize(1);
        ConflictResolutionEntity ticket = conflicts.get(0);
        assertThat(ticket.getStatus()).isEqualTo("DETECTED");
        assertThat(ticket.getSource()).isEqualTo("ONLINE_GUARD");
        assertThat(Set.of(ticket.getChunkIdA(), ticket.getChunkIdB()))
                .containsExactlyInAnyOrder("c-a", "c-b");
    }

    @Test
    void 冲突对已存在任意状态时不再重复写单() {
        List<KbDocument> docs = List.of(
                docOf("docA", LocalDate.of(2025, 1, 1)),
                docOf("docB", LocalDate.of(2026, 1, 1)));
        RetrievalConflictGuard guard = newGuard(true, null, docs, similarVectors());
        when(judge.judge(anyString(), anyList())).thenReturn(Optional.of(
                new ConflictVerdict("c-a", "c-b", "苹果", "售价", "5元", "3元")));
        // 既有冲突单（反向 + 已处置状态）→ 不重复写
        ConflictResolutionEntity existing = new ConflictResolutionEntity();
        existing.setChunkIdA("c-b");
        existing.setChunkIdB("c-a");
        existing.setStatus("RESOLVED");
        when(conflictDao.findAll()).thenReturn(List.of(existing));

        GuardResult result = guard.apply("苹果多少钱", List.of(
                view("c-a", "docA", TEXT_A), view("c-b", "docB", TEXT_B)));

        assertThat(result.kept()).extracting(RepChunkView::childId).containsExactly("c-b");
        verify(conflictDao, never()).save(any(ConflictResolutionEntity.class));
        // 移除打点仍然记录（每次裁决都可审计）
        assertThat(savedMetrics()).anyMatch(m -> "CONFLICT_GUARD_REMOVED".equals(m.getEventType())
                && "c-a".equals(m.getChunkId()));
    }

    @Test
    void 生效时间相同_双保留并标注冲突提示() {
        List<KbDocument> docs = List.of(
                docOf("docA", LocalDate.of(2025, 1, 1)),
                docOf("docB", LocalDate.of(2025, 1, 1)));
        RetrievalConflictGuard guard = newGuard(true, null, docs, similarVectors());
        when(judge.judge(anyString(), anyList())).thenReturn(Optional.of(
                new ConflictVerdict("c-a", "c-b", "苹果", "售价", "5元", "3元")));
        when(conflictDao.findAll()).thenReturn(List.of());

        GuardResult result = guard.apply("苹果多少钱", List.of(
                view("c-a", "docA", TEXT_A), view("c-b", "docB", TEXT_B)));

        assertThat(result.kept()).extracting(RepChunkView::childId)
                .containsExactly("c-a", "c-b");
        assertThat(result.conflictNote()).isNotBlank();
        assertThat(result.conflictNote()).contains("生效于2025-01-01").contains("冲突")
                .contains("docA.txt").contains("docB.txt");
        assertThat(savedMetrics()).anyMatch(m -> "CONFLICT_GUARD_KEPT_BOTH".equals(m.getEventType()));
        // 涉冲突仍写 DETECTED 冲突单（人工仲裁闭环入口）
        assertThat(savedConflicts()).hasSize(1);
    }

    @Test
    void 一方生效时间缺失_双保留不默认选边() {
        List<KbDocument> docs = List.of(
                docOf("docA", LocalDate.of(2025, 1, 1)),
                docOf("docB", null));
        RetrievalConflictGuard guard = newGuard(true, null, docs, similarVectors());
        when(judge.judge(anyString(), anyList())).thenReturn(Optional.of(
                new ConflictVerdict("c-a", "c-b", "苹果", "售价", "5元", "3元")));
        when(conflictDao.findAll()).thenReturn(List.of());

        GuardResult result = guard.apply("苹果多少钱", List.of(
                view("c-a", "docA", TEXT_A), view("c-b", "docB", TEXT_B)));

        assertThat(result.kept()).extracting(RepChunkView::childId)
                .containsExactly("c-a", "c-b");
        assertThat(result.conflictNote()).contains("生效于2025-01-01").contains("冲突");
        assertThat(savedMetrics()).anyMatch(m -> "CONFLICT_GUARD_KEPT_BOTH".equals(m.getEventType()));
        assertThat(savedMetrics()).noneMatch(m -> "CONFLICT_GUARD_REMOVED".equals(m.getEventType()));
    }

    @Test
    void 传递冲突组_只保留组内生效最晚者() {
        // A~B、B~C 两两冲突（A~C 未判冲突）：并入一组，只留 eff 最晚的 C
        List<KbDocument> docs = List.of(
                docOf("docA", LocalDate.of(2025, 1, 1)),
                docOf("docB", LocalDate.of(2026, 1, 1)),
                docOf("docC", LocalDate.of(2027, 1, 1)));
        RetrievalConflictGuard guard = newGuard(true, null, docs, similarVectors());
        when(judge.judge(anyString(), anyList())).thenAnswer(inv -> {
            List<RepChunkView> pair = inv.getArgument(1);
            Set<String> ids = Set.of(pair.get(0).childId(), pair.get(1).childId());
            if (ids.equals(Set.of("c-a", "c-b")) || ids.equals(Set.of("c-b", "c-c"))) {
                return Optional.of(new ConflictVerdict(pair.get(0).childId(), pair.get(1).childId(),
                        "苹果", "售价", "x", "y"));
            }
            return Optional.empty();
        });
        when(conflictDao.findAll()).thenReturn(List.of());

        GuardResult result = guard.apply("苹果多少钱", List.of(
                view("c-a", "docA", TEXT_A), view("c-b", "docB", TEXT_B), view("c-c", "docC", TEXT_C)));

        assertThat(result.kept()).extracting(RepChunkView::childId).containsExactly("c-c");
        List<MetricEventEntity> metrics = savedMetrics();
        assertThat(metrics).anyMatch(m -> "CONFLICT_GUARD_REMOVED".equals(m.getEventType())
                && "c-a".equals(m.getChunkId()));
        assertThat(metrics).anyMatch(m -> "CONFLICT_GUARD_REMOVED".equals(m.getEventType())
                && "c-b".equals(m.getChunkId()));
    }

    @Test
    void 守卫开关关闭_原样透传零副作用() {
        RetrievalConflictGuard guard = newGuard(false, null, List.of(), similarVectors());
        List<RepChunkView> candidates = List.of(
                view("c-a", "docA", TEXT_A), view("c-b", "docB", TEXT_B));

        GuardResult result = guard.apply("苹果多少钱", candidates);

        assertThat(result.kept()).isSameAs(candidates);
        assertThat(result.conflictNote()).isNull();
        assertThat(result.verdicts()).isEmpty();
        verifyNoInteractions(judge, metricDao, conflictDao, embeddingModel, docRepo);
    }

    @Test
    void 灰度未命中_原样透传零副作用() {
        GrayReleaseService gray = mock(GrayReleaseService.class);
        when(gray.isEnabled(eq("conflict-guard"), any())).thenReturn(false);
        RetrievalConflictGuard guard = newGuard(true, gray, List.of(
                docOf("docA", LocalDate.of(2025, 1, 1)),
                docOf("docB", LocalDate.of(2026, 1, 1))), similarVectors());
        List<RepChunkView> candidates = List.of(
                view("c-a", "docA", TEXT_A), view("c-b", "docB", TEXT_B));

        GuardResult result = guard.apply("苹果多少钱", candidates);

        assertThat(result.kept()).isSameAs(candidates);
        assertThat(result.conflictNote()).isNull();
        verifyNoInteractions(judge, metricDao, conflictDao, embeddingModel);
    }

    @Test
    void 粗筛无疑似对_不调用judge() {
        // 正交向量（余弦 0 < 0.85）→ 粗筛零命中，不得进入 LLM 精判
        Map<String, float[]> orthogonal = Map.of(
                TEXT_A, new float[]{1f, 0f}, TEXT_B, new float[]{0f, 1f});
        RetrievalConflictGuard guard = newGuard(true, null, List.of(
                docOf("docA", LocalDate.of(2025, 1, 1)),
                docOf("docB", LocalDate.of(2026, 1, 1))), orthogonal);
        List<RepChunkView> candidates = List.of(
                view("c-a", "docA", TEXT_A), view("c-b", "docB", TEXT_B));

        GuardResult result = guard.apply("苹果多少钱", candidates);

        assertThat(result.kept()).isEqualTo(candidates);
        assertThat(result.conflictNote()).isNull();
        assertThat(result.verdicts()).isEmpty();
        verify(judge, never()).judge(anyString(), anyList());
        verifyNoInteractions(metricDao, conflictDao);
    }

    // ---------- 检索链路集成（RetrievalService 接线） ----------

    private static KbParentChunk parent(String id, String docId, String content) {
        KbParentChunk p = new KbParentChunk();
        p.setId(id);
        p.setDocId(docId);
        p.setContent(content);
        return p;
    }

    private static KbChildChunk child(String id, String parentId, String docId, String content) {
        KbChildChunk c = new KbChildChunk();
        c.setId(id);
        c.setParentId(parentId);
        c.setDocId(docId);
        c.setChildIndex(0);
        c.setContent(content);
        c.setActive(true);
        return c;
    }

    @Test
    void 检索链路集成_输家chunk所在父块被剔除且冲突标注拼进上下文() {
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(
                parent("p1", "d1", "旧文档父块：苹果售价5元"), parent("p2", "d2", "新文档父块：苹果售价3元")));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(
                child("c-a", "p1", "d1", TEXT_A), child("c-b", "p2", "d2", TEXT_B)));
        when(docRepo.findAllById(any())).thenAnswer(inv -> {
            List<KbDocument> out = new ArrayList<>();
            for (Object id : inv.getArgument(0, Iterable.class)) {
                KbDocument d = docOf((String) id, null);
                out.add(d);
            }
            return out;
        });

        RetrievalConflictGuard guard = mock(RetrievalConflictGuard.class);
        when(guard.apply(eq("苹果多少钱"), anyList())).thenAnswer(inv -> {
            List<RepChunkView> cands = inv.getArgument(1);
            assertThat(cands).extracting(RepChunkView::childId)
                    .containsExactlyInAnyOrder("c-a", "c-b");
            List<RepChunkView> kept = cands.stream()
                    .filter(c -> "c-b".equals(c.childId())).toList();
            return new GuardResult(kept, "【冲突提示】来源存在冲突", List.of());
        });

        WikiAgentProperties props = new WikiAgentProperties(null,
                new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);
        RetrievalService svc = new RetrievalService(props, mock(MilvusStoreService.class),
                embeddingModel, parentRepo, docRepo, childRepo, mock(MetricEventJpaDao.class),
                providerOf(null), providerOf((KnowledgeMetadataJpaDao) null), false,
                providerOf(null), providerOf(null), providerOf(null), providerOf(guard));

        RetrievalService.Accumulator acc = svc.newAccumulator();
        acc.put("p1", "d1", "c-a", 0, 0.9);
        acc.put("p2", "d2", "c-b", 0, 0.8);

        RetrievalService.RetrievalResult result = svc.assemble(acc, "苹果多少钱");

        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().get(0).docId()).isEqualTo("d2");
        assertThat(result.context()).contains("新文档父块：苹果售价3元");
        assertThat(result.context()).doesNotContain("旧文档父块");
        assertThat(result.context()).contains("【冲突提示】来源存在冲突");
    }
}
