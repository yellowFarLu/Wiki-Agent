package com.wikiagent.service.retrieve;

import com.wikiagent.application.knowledge.KnowledgeTagContext;
import com.wikiagent.application.knowledge.dedup.NearDuplicateGuard;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.infrastructure.persistence.ConflictResolutionEntity;
import com.wikiagent.infrastructure.persistence.ConflictResolutionJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.service.ingest.IngestionService;
import com.wikiagent.service.store.MilvusStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Task 10：RAG 去重 + 冲突守卫端到端流程 IT（H2 + stub ChatModel，独立 H2 URL 与 flyway 库隔离）。
 * <p>
 * 场景覆盖五层防御中最关键的串联路径：
 * <ol>
 *   <li>入库：旧文档「苹果售价5元。」（生效 2025-01-01）→ 新文档「苹果售价3元。」（生效 2026-01-01）。
 *       dedup 开启：L0 精确 hash 不命中（内容不同）；L1 MinHash-LSH 命中近重复（仅一个数字差异，
 *       jaccard ≥ 0.9）但关键 token 差异（5 vs 3）→ 不自动合并，双方保持 active，
 *       写 source=MINHASH_INGEST 冲突单（DETECTED，hint=CONFLICT）</li>
 *   <li>检索「苹果多少钱」：本机无 Milvus，milvus 桩抛异常 → 本地关键词降级召回两个父块；
 *       conflict guard 开启 + stub intentChatModel 精判确认冲突 → 按 kb_document.effective_date
 *       确定性裁决，新文档（3 元）胜出，旧文档父块整体从候选剔除</li>
 *   <li>断言：上下文只含「3 元」；conflict_resolution 恰 1 条 MINHASH_INGEST 单（pairKey
 *       无序对跨 source 去重，检索守卫不重复报单，但打点照常）；metric_event 计数正确
 *       （DETECTED×2、REMOVED×1（输家 chunk）、KEPT_BOTH×0、DEDUP_NEAR_MERGED×0、
 *       DEDUP_EXACT_SKIPPED×0）</li>
 * </ol>
 * <p>
 * 诚实声明：本 IT 不覆盖 Milvus 真实召回（Testcontainers 链路在 CI 的 TaskFrameworkE2eIT），
 * 检索段走代码内已有的本地关键词降级路径；守卫裁决、冲突单与打点为全量真实 DB 验证。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update",
        "wikiagent.dedup.enabled=true",
        "wikiagent.conflict-guard.enabled=true"
})
class ConflictGuardFlowIT {

    private static final String TEXT_OLD = "苹果售价5元。";
    private static final String TEXT_NEW = "苹果售价3元。";
    private static final String QUERY = "苹果多少钱";
    /** stub 精判：恒确认冲突（苹果/售价/5元 vs 3元），chunkId 由 judge 取输入对。 */
    private static final String CONFLICT_JSON =
            "{\"conflict\":true,\"entity\":\"苹果\",\"attribute\":\"售价\","
                    + "\"valueA\":\"5元\",\"valueB\":\"3元\"}";

    /** 独立 H2 文件（flyway 关闭的上下文不得污染默认库，否则后续 flyway 迁移会因列已存在而失败）。 */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-conflict-guard-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL");
    }

    /**
     * 内存版 StringRedisTemplate：本机无 Redis，但 L1 近重复守卫依赖 RedisLshIndex 做
     * MinHash 签名分桶协调，必须供给真实可用的 Set/String 语义（否则守卫降级跳过，
     * 写不出 MINHASH_INGEST 冲突单）。
     * <p>
     * 经由独立连接工厂 Bean 触发 Spring Boot Redis 自动装配的 @ConditionalOnMissingBean
     * 回退（wikiagent.redis.enabled=false 只排除自动配置类，不排斥手工装配）；
     * 工厂抛异常但从不被调用，避免真实连接。仅覆盖 LSH 用到的 4 个操作，其余不支持。
     */
    @TestConfiguration
    static class InMemoryRedisConfig {

        @Bean
        RedisConnectionFactory testRedisConnectionFactory() {
            RedisConnectionFactory factory = org.mockito.Mockito.mock(RedisConnectionFactory.class);
            when(factory.getConnection()).thenThrow(
                    new IllegalStateException("IT 内存 Redis 不提供真实连接"));
            return factory;
        }

        /** Map 支撑的 band 桶 stub（沿用 NearDuplicateGuardTest 的 fake 思路，覆盖 LSH 用到的 4 个操作）。 */
        @Bean(name = "stringRedisTemplate")
        @Primary
        StringRedisTemplate inMemoryStringRedisTemplate() {
            Map<String, Set<String>> buckets = new ConcurrentHashMap<>();
            Map<String, String> values = new ConcurrentHashMap<>();
            StringRedisTemplate template = org.mockito.Mockito.mock(StringRedisTemplate.class);
            org.springframework.data.redis.core.SetOperations<String, String> setOps =
                    org.mockito.Mockito.mock(org.springframework.data.redis.core.SetOperations.class);
            org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                    org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
            when(template.opsForSet()).thenReturn(setOps);
            when(template.opsForValue()).thenReturn(valueOps);
            when(setOps.add(anyString(), anyString())).thenAnswer(inv -> {
                buckets.computeIfAbsent(inv.getArgument(0), k -> ConcurrentHashMap.newKeySet())
                        .add(inv.getArgument(1));
                return 1L;
            });
            when(setOps.members(anyString())).thenAnswer(inv ->
                    buckets.getOrDefault(inv.getArgument(0), Set.of()));
            when(setOps.remove(anyString(), anyString())).thenAnswer(inv -> {
                Set<String> bucket = buckets.get(inv.getArgument(0));
                if (bucket != null) {
                    bucket.remove(inv.getArgument(1));
                }
                return 1L;
            });
            org.mockito.Mockito.doAnswer(inv -> {
                values.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(valueOps).set(anyString(), anyString());
            when(template.delete(anyString())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                boolean removed = values.remove(key) != null;
                removed |= buckets.remove(key) != null;
                return removed;
            });
            return template;
        }
    }

    /** Milvus 缺席：入库 insert 为 no-op，检索 hybridSearch 抛异常触发本地关键词降级。 */
    @MockBean
    private MilvusStoreService milvus;

    /** 向量桩：所有文本恒同一向量（粗筛余弦=1.0 ≥ 0.85，必进精判）。 */
    @MockBean
    private EmbeddingModel embeddingModel;

    /** 精判桩：替换 intentChatModel（ConflictLlmJudge 按名注入），恒返回冲突 JSON。 */
    @MockBean(name = "intentChatModel")
    private ChatModel intentChatModel;

    @Autowired
    private IngestionService ingestion;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private KbDocumentRepo docRepo;

    @Autowired
    private KbChildChunkRepo childRepo;

    @Autowired
    private ConflictResolutionJpaDao conflictDao;

    @Autowired
    private MetricEventJpaDao metricDao;

    private final List<String> createdDocIds = new ArrayList<>();

    @BeforeEach
    void setUpStubs() {
        float[] vec = new float[1024];
        vec[0] = 1f;
        when(embeddingModel.embed(anyString())).thenReturn(vec);
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> vec).collect(Collectors.toList());
        });
        // RetrievalService 走 6 参重载；未 stub 的重载 Mockito 默认返回空 List（不抛异常、不触发降级）
        when(milvus.hybridSearch(any(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenThrow(new RuntimeException("IT 环境无 Milvus"));
        when(intentChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(CONFLICT_JSON)))));
    }

    @AfterEach
    void cleanup() {
        for (String docId : createdDocIds) {
            try {
                ingestion.delete(docId); // milvus 桩 no-op；清 DB 行 + data/uploads 目录
            } catch (Exception ignored) {
                // 清理失败不影响结果
            }
        }
        createdDocIds.clear();
    }

    @Test
    void 近重复冲突双文档入库不合并_检索守卫按生效时间剔除旧值() {
        // ===== 1. 两份文档入库（旧 5 元 / 新 3 元，生效时间旧 < 新）=====
        String docOld = ingestDoc("apple-old.txt", TEXT_OLD, LocalDate.of(2025, 1, 1));
        String docNew = ingestDoc("apple-new.txt", TEXT_NEW, LocalDate.of(2026, 1, 1));

        KbChildChunk chunkOld = onlyActiveChild(docOld);
        KbChildChunk chunkNew = onlyActiveChild(docNew);
        // L1 关键 token 差异 → 不自动合并：双方保持 active
        assertThat(chunkOld.getContent()).isEqualTo(TEXT_OLD);
        assertThat(chunkNew.getContent()).isEqualTo(TEXT_NEW);
        // L0/L1 均写 content_hash（开关开启路径）
        assertThat(chunkOld.getContentHash()).isNotBlank();
        assertThat(chunkNew.getContentHash()).isNotBlank();
        assertThat(chunkOld.getContentHash()).isNotEqualTo(chunkNew.getContentHash());

        // 入库侧冲突单：MINHASH_INGEST（pairKey 无序对去重 → 恰好 1 条）
        List<ConflictResolutionEntity> afterIngest = conflictDao.findAll();
        assertThat(afterIngest).hasSize(1);
        ConflictResolutionEntity ingestTicket = afterIngest.get(0);
        assertThat(ingestTicket.getSource()).isEqualTo(NearDuplicateGuard.CONFLICT_SOURCE);
        assertThat(ingestTicket.getStatus()).isEqualTo("DETECTED");
        assertThat(ingestTicket.getResolutionHint()).isEqualTo(NearDuplicateGuard.HINT_CONFLICT);
        assertThat(List.of(ingestTicket.getChunkIdA(), ingestTicket.getChunkIdB()))
                .containsExactlyInAnyOrder(chunkOld.getId(), chunkNew.getId());

        // ===== 2. 检索「苹果多少钱」（守卫开启 + 精判确认冲突）=====
        RetrievalService.RetrievalResult result = retrievalService.retrieve(QUERY);

        // 上下文只含新文档（3 元）；输家旧文档整体剔除
        assertThat(result.context()).contains(TEXT_NEW);
        assertThat(result.context()).doesNotContain("5元");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().get(0).docId()).isEqualTo(docNew);

        // ===== 3. 审计面：冲突单 + metric_event =====
        // pairKey 无序对跨 source 去重（Task 7 锁定语义）：同一 chunk 对已持 MINHASH_INGEST 单，
        // 检索守卫不再重复写 ONLINE_GUARD 单，但 DETECTED/REMOVED 打点照常 → 冲突单仍恰 1 条
        List<ConflictResolutionEntity> tickets = conflictDao.findAll();
        assertThat(tickets).hasSize(1);
        ConflictResolutionEntity onlyTicket = tickets.get(0);
        assertThat(onlyTicket.getSource()).isEqualTo(NearDuplicateGuard.CONFLICT_SOURCE);
        assertThat(onlyTicket.getStatus()).isEqualTo("DETECTED");
        assertThat(List.of(onlyTicket.getChunkIdA(), onlyTicket.getChunkIdB()))
                .containsExactlyInAnyOrder(chunkOld.getId(), chunkNew.getId());

        // metric_event 计数：DETECTED×2（双方各一）+ REMOVED×1（输家=旧 chunk）
        Map<String, Long> byType = metricDao.findAll().stream()
                .map(MetricEventEntity::getEventType)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(byType.getOrDefault(RetrievalConflictGuard.EVENT_DETECTED, 0L)).isEqualTo(2);
        assertThat(byType.getOrDefault(RetrievalConflictGuard.EVENT_REMOVED, 0L)).isEqualTo(1);
        assertThat(byType.getOrDefault(RetrievalConflictGuard.EVENT_KEPT_BOTH, 0L)).isEqualTo(0);
        assertThat(byType.getOrDefault(NearDuplicateGuard.EVENT_DEDUP_NEAR_MERGED, 0L)).isEqualTo(0);
        assertThat(byType.getOrDefault("DEDUP_EXACT_SKIPPED", 0L)).isEqualTo(0);

        // chunkId 必须是真实 chunk（禁止伪造）：输家 REMOVED 打在旧 chunk 上
        List<MetricEventEntity> loserEvents = metricDao.findByChunkId(chunkOld.getId());
        assertThat(loserEvents).anyMatch(e -> RetrievalConflictGuard.EVENT_REMOVED.equals(e.getEventType()));
        assertThat(loserEvents).anyMatch(e -> RetrievalConflictGuard.EVENT_DETECTED.equals(e.getEventType()));
        List<MetricEventEntity> winnerEvents = metricDao.findByChunkId(chunkNew.getId());
        assertThat(winnerEvents).anyMatch(e -> RetrievalConflictGuard.EVENT_DETECTED.equals(e.getEventType()));
        assertThat(winnerEvents).noneMatch(e -> RetrievalConflictGuard.EVENT_REMOVED.equals(e.getEventType()));
    }

    /**
     * 同步走完整入库流水线（parse→clean→split(L0)→embedAndPersist(L1)→finalize）。
     * 逐步调用代理 Bean（与 IngestTaskHandler 生产驱动方式一致）——runPipeline 自调用
     * 会绕过 @Transactional 代理，deactivateByDocId 更新查询将无事务报错。
     */
    private String ingestDoc(String filename, String content, LocalDate effectiveDate) {
        String docId = "doc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setFilename(filename);
        doc.setDocType("txt");
        doc.setSizeBytes(bytes.length);
        doc.setStatus(KbDocument.PARSING);
        doc.setEffectiveDate(effectiveDate);
        docRepo.save(doc);
        createdDocIds.add(docId);

        String raw = ingestion.parseStep(docId, filename, bytes);
        String cleaned = ingestion.cleanStep(docId, raw);
        IngestionService.IngestOutcome outcome = ingestion.splitStep(
                docId, cleaned, KnowledgeTagContext.defaultFor(filename));
        ingestion.embedAndPersistStep(docId);
        ingestion.finalizeStep(docId);
        assertThat(outcome.childCount()).isEqualTo(1);
        return docId;
    }

    private KbChildChunk onlyActiveChild(String docId) {
        List<KbChildChunk> chunks = childRepo.findByDocIdAndActiveTrue(docId);
        assertThat(chunks).as("docId=%s 应恰好 1 个 active 子块", docId).hasSize(1);
        return chunks.get(0);
    }
}
