package com.wikiagent.application.knowledge.dedup;

import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.infrastructure.persistence.ConflictResolutionEntity;
import com.wikiagent.infrastructure.persistence.ConflictResolutionJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L1 入库近重复守卫：MinHash-LSH 粗筛 + 精确 jaccard 复判 + KeyTokenDiffer 分流。
 * <p>
 * 分流语义：非关键差异自动合并（生效日晚者留 active，相同/缺失留旧，与 L0 一致）并打
 * DEDUP_NEAR_MERGED metric；关键 token 差异双方不动、写 conflict_resolution
 * （status=DETECTED, source=MINHASH_INGEST, resolution_hint=CONFLICT，pairKey 无序对去重）。
 * Redis 异常降级跳过不阻断入库。
 */
@ExtendWith(MockitoExtension.class)
class NearDuplicateGuardTest {

    /** 49 字正文（与 MinHashSignatureTest 同一基准，差 1 字 jaccard ≥ 0.9）。 */
    private static final String BASE =
            "红富士苹果新鲜上市产地直供口感脆甜欢迎选购本周特惠活动正在进行中数量有限售完即止价格实惠品质保证";
    /** 与 BASE 仅差 1 个非关键虚词（保证→保障）。 */
    private static final String NON_CRITICAL_DIFF =
            "红富士苹果新鲜上市产地直供口感脆甜欢迎选购本周特惠活动正在进行中数量有限售完即止价格实惠品质保障";
    /** 关键 token（金额）版本对：5元 vs 3元。 */
    private static final String CRITICAL_A = BASE + "售价5元每箱";
    private static final String CRITICAL_B = BASE + "售价3元每箱";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private KbChildChunkRepo childRepo;
    @Mock
    private KbDocumentRepo docRepo;
    @Mock
    private ConflictResolutionJpaDao conflictDao;
    @Mock
    private MetricEventJpaDao metricDao;

    /** Map 支撑的 band 桶 mock（沿用 RedisLshIndexTest 的 fake 思路），按调用面选择 stub 避免 STRICT_STUBS 误报。 */
    private Map<String, Set<String>> stubBandBuckets(boolean withQuery, boolean withRemove) {
        Map<String, Set<String>> buckets = new HashMap<>();
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.add(anyString(), anyString())).thenAnswer(inv -> {
            buckets.computeIfAbsent(inv.getArgument(0), k -> new HashSet<>())
                    .add(inv.getArgument(1));
            return 1L;
        });
        if (withQuery) {
            when(setOps.members(anyString())).thenAnswer(inv ->
                    buckets.getOrDefault(inv.getArgument(0), Set.of()));
        }
        if (withRemove) {
            when(setOps.remove(anyString(), anyString())).thenAnswer(inv -> {
                Set<String> bucket = buckets.get(inv.getArgument(0));
                if (bucket != null) {
                    bucket.remove(inv.getArgument(1));
                }
                return 1L;
            });
        }
        return buckets;
    }

    /** 真实 RedisLshIndex（Map 桶 mock 托底），put 的签名持久化走 valueOps。 */
    private RedisLshIndex realLsh() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        return new RedisLshIndex(redisTemplate);
    }

    private NearDuplicateGuard newGuard(boolean enabled, RedisLshIndex lsh) {
        WikiAgentProperties props = new WikiAgentProperties(null, null, null, null,
                new WikiAgentProperties.Dedup(enabled, 0.9));
        return new NearDuplicateGuard(props, childRepo, docRepo, conflictDao, metricDao, lsh);
    }

    private KbDocument docOf(String id, LocalDate effectiveDate) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setFilename(id + ".txt");
        d.setDocType("txt");
        d.setEffectiveDate(effectiveDate);
        return d;
    }

    private KbChildChunk activeChunkOf(String id, String docId, String content) {
        KbChildChunk c = new KbChildChunk();
        c.setId(id);
        c.setDocId(docId);
        c.setParentId("p-" + docId);
        c.setChildIndex(0);
        c.setContent(content);
        c.setVersionNo(1);
        c.setActive(true);
        return c;
    }

    @Test
    void 非关键差异且新文档生效晚时旧块下线新块保持并打metric() {
        stubBandBuckets(true, true);
        RedisLshIndex lsh = realLsh();
        KbDocument docA = docOf("docA", LocalDate.of(2025, 1, 1));
        KbDocument docB = docOf("docB", LocalDate.of(2026, 1, 1));
        KbChildChunk chunkX = activeChunkOf("chunkX", "docA", BASE);
        KbChildChunk chunkY = activeChunkOf("chunkY", "docB", NON_CRITICAL_DIFF);
        lsh.put("chunkX", MinHashSignature.of(BASE));  // 历史 active chunk 签名已入桶

        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of(chunkY));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(chunkX));
        when(docRepo.findById("docB")).thenReturn(Optional.of(docB));
        when(docRepo.findById("docA")).thenReturn(Optional.of(docA));

        int pairs = newGuard(true, lsh).inspect("docB");

        assertThat(pairs).isEqualTo(1);
        assertThat(chunkX.isActive()).isFalse();
        assertThat(chunkY.isActive()).isTrue();
        verify(childRepo).save(chunkX);
        ArgumentCaptor<MetricEventEntity> metric = ArgumentCaptor.forClass(MetricEventEntity.class);
        verify(metricDao).save(metric.capture());
        assertThat(metric.getValue().getEventType()).isEqualTo("DEDUP_NEAR_MERGED");
        assertThat(metric.getValue().getChunkId()).isEqualTo("chunkX");
        verify(conflictDao, never()).save(any());
        // 输家签名从 LSH 移除、存活的新 chunk 签名入桶
        assertThat(lsh.query(MinHashSignature.of(BASE))).doesNotContain("chunkX");
        assertThat(lsh.query(MinHashSignature.of(NON_CRITICAL_DIFF))).contains("chunkY");
    }

    @Test
    void 关键token差异时双方不动并写冲突单() {
        stubBandBuckets(true, false);
        RedisLshIndex lsh = realLsh();
        KbChildChunk chunkX = activeChunkOf("chunkX", "docA", CRITICAL_A);
        KbChildChunk chunkY = activeChunkOf("chunkY", "docB", CRITICAL_B);
        lsh.put("chunkX", MinHashSignature.of(CRITICAL_A));

        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of(chunkY));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(chunkX));
        when(conflictDao.findAll()).thenReturn(List.of());

        int pairs = newGuard(true, lsh).inspect("docB");

        assertThat(pairs).isEqualTo(1);
        assertThat(chunkX.isActive()).isTrue();
        assertThat(chunkY.isActive()).isTrue();
        verify(childRepo, never()).save(any());
        verify(metricDao, never()).save(any());
        ArgumentCaptor<ConflictResolutionEntity> cap = ArgumentCaptor.forClass(ConflictResolutionEntity.class);
        verify(conflictDao).save(cap.capture());
        ConflictResolutionEntity e = cap.getValue();
        assertThat(e.getChunkIdA()).isEqualTo("chunkX");
        assertThat(e.getChunkIdB()).isEqualTo("chunkY");
        assertThat(e.getStatus()).isEqualTo("DETECTED");
        assertThat(e.getSource()).isEqualTo("MINHASH_INGEST");
        assertThat(e.getResolutionHint()).isEqualTo("CONFLICT");
        assertThat(e.getSimilarity()).isNotNull();
        // 双方存活：新 chunk 签名照常入桶
        assertThat(lsh.query(MinHashSignature.of(CRITICAL_B))).contains("chunkY");
    }

    @Test
    void 新文档生效日早于旧文档时新块下线() {
        stubBandBuckets(true, true);
        RedisLshIndex lsh = realLsh();
        KbDocument docA = docOf("docA", LocalDate.of(2026, 1, 1));
        KbDocument docB = docOf("docB", LocalDate.of(2025, 1, 1));
        KbChildChunk chunkX = activeChunkOf("chunkX", "docA", BASE);
        KbChildChunk chunkY = activeChunkOf("chunkY", "docB", NON_CRITICAL_DIFF);
        lsh.put("chunkX", MinHashSignature.of(BASE));

        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of(chunkY));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(chunkX));
        when(docRepo.findById("docB")).thenReturn(Optional.of(docB));
        when(docRepo.findById("docA")).thenReturn(Optional.of(docA));

        int pairs = newGuard(true, lsh).inspect("docB");

        assertThat(pairs).isEqualTo(1);
        assertThat(chunkY.isActive()).isFalse();
        assertThat(chunkX.isActive()).isTrue();
        verify(childRepo).save(chunkY);
        ArgumentCaptor<MetricEventEntity> metric = ArgumentCaptor.forClass(MetricEventEntity.class);
        verify(metricDao).save(metric.capture());
        assertThat(metric.getValue().getEventType()).isEqualTo("DEDUP_NEAR_MERGED");
        assertThat(metric.getValue().getChunkId()).isEqualTo("chunkY");
        // 输家（新块）不入桶；旧块签名仍在
        assertThat(lsh.query(MinHashSignature.of(NON_CRITICAL_DIFF))).doesNotContain("chunkY");
        assertThat(lsh.query(MinHashSignature.of(BASE))).contains("chunkX");
    }

    @Test
    void 生效日期缺失时保留旧块新块下线() {
        stubBandBuckets(true, true);
        RedisLshIndex lsh = realLsh();
        KbDocument docA = docOf("docA", null);
        KbDocument docB = docOf("docB", null);
        KbChildChunk chunkX = activeChunkOf("chunkX", "docA", BASE);
        KbChildChunk chunkY = activeChunkOf("chunkY", "docB", NON_CRITICAL_DIFF);
        lsh.put("chunkX", MinHashSignature.of(BASE));

        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of(chunkY));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(chunkX));
        when(docRepo.findById("docB")).thenReturn(Optional.of(docB));
        when(docRepo.findById("docA")).thenReturn(Optional.of(docA));

        int pairs = newGuard(true, lsh).inspect("docB");

        assertThat(pairs).isEqualTo(1);
        assertThat(chunkX.isActive()).isTrue();
        assertThat(chunkY.isActive()).isFalse();
        verify(childRepo).save(chunkY);
    }

    @Test
    void 同docId候选命中时跳过() {
        stubBandBuckets(true, false);
        RedisLshIndex lsh = realLsh();
        KbChildChunk chunkY = activeChunkOf("chunkY", "docB", NON_CRITICAL_DIFF);
        KbChildChunk chunkZ = activeChunkOf("chunkZ", "docB", BASE);  // 同文档历史 chunk 已在 LSH
        lsh.put("chunkZ", MinHashSignature.of(BASE));

        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of(chunkY));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(chunkZ));

        int pairs = newGuard(true, lsh).inspect("docB");

        assertThat(pairs).isEqualTo(0);
        assertThat(chunkY.isActive()).isTrue();
        assertThat(chunkZ.isActive()).isTrue();
        verify(childRepo, never()).save(any());
        verifyNoInteractions(metricDao, conflictDao);
    }

    @Test
    void 开关关闭时直接返回0且不触碰任何依赖() {
        RedisLshIndex lsh = new RedisLshIndex(redisTemplate);

        int pairs = newGuard(false, lsh).inspect("docB");

        assertThat(pairs).isEqualTo(0);
        verifyNoInteractions(redisTemplate, childRepo, docRepo, conflictDao, metricDao);
    }

    @Test
    void dedup配置缺失时按关闭处理() {
        WikiAgentProperties legacyProps = new WikiAgentProperties(null, null, null, null);
        NearDuplicateGuard guard = new NearDuplicateGuard(legacyProps, childRepo, docRepo,
                conflictDao, metricDao, new RedisLshIndex(redisTemplate));

        assertThat(guard.inspect("docB")).isEqualTo(0);
        verifyNoInteractions(redisTemplate, childRepo, docRepo, conflictDao, metricDao);
    }

    @Test
    void Redis异常时降级跳过不阻断() {
        when(redisTemplate.opsForSet()).thenThrow(new RuntimeException("Redis down"));
        RedisLshIndex lsh = new RedisLshIndex(redisTemplate);
        KbChildChunk chunkY = activeChunkOf("chunkY", "docB", NON_CRITICAL_DIFF);
        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of(chunkY));

        int pairs = newGuard(true, lsh).inspect("docB");  // 不抛出

        assertThat(pairs).isEqualTo(0);
        assertThat(chunkY.isActive()).isTrue();
        verify(childRepo, never()).save(any());
        verifyNoInteractions(metricDao, conflictDao);
    }

    @Test
    void 冲突对已存在时按无序对去重不重复写单() {
        stubBandBuckets(true, false);
        RedisLshIndex lsh = realLsh();
        KbChildChunk chunkX = activeChunkOf("chunkX", "docA", CRITICAL_A);
        KbChildChunk chunkY = activeChunkOf("chunkY", "docB", CRITICAL_B);
        lsh.put("chunkX", MinHashSignature.of(CRITICAL_A));
        ConflictResolutionEntity existing = new ConflictResolutionEntity();
        existing.setChunkIdA("chunkY");  // 反向也视为同一对（无序对规则）
        existing.setChunkIdB("chunkX");

        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of(chunkY));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(chunkX));
        when(conflictDao.findAll()).thenReturn(List.of(existing));

        int pairs = newGuard(true, lsh).inspect("docB");

        assertThat(pairs).isEqualTo(0);
        verify(conflictDao, never()).save(any());
        assertThat(chunkX.isActive()).isTrue();
        assertThat(chunkY.isActive()).isTrue();
    }

    @Test
    void 桶内候选精确jaccard低于阈值时不认定() {
        RedisLshIndex lsh = mock(RedisLshIndex.class);
        KbChildChunk chunkY = activeChunkOf("chunkY", "docB", NON_CRITICAL_DIFF);
        KbChildChunk chunkW = activeChunkOf("chunkW", "docC",
                "量子计算机通过叠加态实现并行计算，与经典计算机架构完全不同。");
        when(lsh.query(any())).thenReturn(Set.of("chunkW"));  // LSH 桶碰撞候选
        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of(chunkY));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(chunkW));

        int pairs = newGuard(true, lsh).inspect("docB");

        assertThat(pairs).isEqualTo(0);
        assertThat(chunkY.isActive()).isTrue();
        assertThat(chunkW.isActive()).isTrue();
        verify(childRepo, never()).save(any());
        verifyNoInteractions(metricDao, conflictDao);
        verify(lsh).put(eq("chunkY"), any());  // 存活 chunk 签名照常入桶
    }

    @Test
    void 超短chunk空签名时跳过LSH不报错() {
        RedisLshIndex lsh = mock(RedisLshIndex.class);
        KbChildChunk chunkY = activeChunkOf("chunkY", "docB", "苹");

        when(childRepo.findByDocIdAndActiveTrue("docB")).thenReturn(List.of(chunkY));

        int pairs = newGuard(true, lsh).inspect("docB");

        assertThat(pairs).isEqualTo(0);
        verifyNoInteractions(lsh);
        verifyNoInteractions(metricDao, conflictDao, docRepo);
    }
}
