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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L1 入库近重复守卫：MinHash-LSH 粗筛 → 桶内精确 jaccard 复判 → KeyTokenDiffer 分流。
 * <p>
 * 流程（{@link #inspect(String)}，由 IngestionService.embedAndPersistStep 在 embedding 之前调用，
 * 命中即省去输家 chunk 的 embedding 费用）：
 * <ol>
 *   <li>读 doc 全部 active chunk，逐条算 MinHash 签名（空签名=超短 chunk，跳过 LSH 不报错）</li>
 *   <li>LSH query 候选 → 桶内精确 jaccard ≥ {@code wikiagent.dedup.near-jaccard}（默认 0.9）才认定，
 *       排除同 docId</li>
 *   <li>KeyTokenDiffer 分流：非关键差异 → 自动合并（生效日晚者留 active，早者 is_active=false 软删；
 *       相同/缺失留旧，与 L0 精确去重一致）；关键 token 差异 → 双方不动，写 conflict_resolution
 *       （status=DETECTED, source=MINHASH_INGEST, resolution_hint=CONFLICT，pairKey 无序对去重）</li>
 *   <li>存活 active chunk 的签名 put 进 LSH（被合并下线者移除/不入桶）</li>
 * </ol>
 * <p>
 * <b>诚实声明 / 已知限制</b>：
 * <ul>
 *   <li>Milvus 不支持 chunk 级删除：合并一律 is_active=false 软删（MySQL 为真相源），
 *       检索侧靠关系库权威过滤</li>
 *   <li>Redis 只做协调（LSH 签名桶）：Redis 缺席（wikiagent.redis.enabled=false）或操作异常时
 *       降级跳过近重复检测并告警，不阻断入库（RedisLshIndex 内部已逐操作降级）</li>
 *   <li>自动合并仅覆盖"非关键虚词差异"的近重复；关键 token（数字/日期/货币/否定词）差异
 *       一律转人工仲裁，不自动选边</li>
 * </ul>
 */
@Service
public class NearDuplicateGuard {

    private static final Logger log = LoggerFactory.getLogger(NearDuplicateGuard.class);

    /** metric_event 事件类型：L1 近重复自动合并（chunkId=被合并下线的输家 chunk）。 */
    public static final String EVENT_DEDUP_NEAR_MERGED = "DEDUP_NEAR_MERGED";
    /** conflict_resolution.source：入库时 MinHash-LSH 检出。 */
    public static final String CONFLICT_SOURCE = "MINHASH_INGEST";
    /** conflict_resolution.resolutionHint：关键 token 差异，建议人工仲裁。 */
    public static final String HINT_CONFLICT = "CONFLICT";

    private final WikiAgentProperties props;
    private final KbChildChunkRepo childRepo;
    private final KbDocumentRepo docRepo;
    private final ConflictResolutionJpaDao conflictDao;
    private final MetricEventJpaDao metricDao;
    /** LSH 索引（null = Redis 缺席，inspect 降级跳过）。 */
    private final RedisLshIndex lsh;

    @Autowired
    public NearDuplicateGuard(WikiAgentProperties props, KbChildChunkRepo childRepo, KbDocumentRepo docRepo,
                              ConflictResolutionJpaDao conflictDao, MetricEventJpaDao metricDao,
                              ObjectProvider<StringRedisTemplate> redis) {
        this(props, childRepo, docRepo, conflictDao, metricDao, toLshIndex(redis));
    }

    /** 测试/直接装配：显式注入 LSH 索引（null = Redis 缺席，inspect 降级跳过）。 */
    public NearDuplicateGuard(WikiAgentProperties props, KbChildChunkRepo childRepo, KbDocumentRepo docRepo,
                              ConflictResolutionJpaDao conflictDao, MetricEventJpaDao metricDao,
                              RedisLshIndex lsh) {
        this.props = props;
        this.childRepo = childRepo;
        this.docRepo = docRepo;
        this.conflictDao = conflictDao;
        this.metricDao = metricDao;
        this.lsh = lsh;
    }

    private static RedisLshIndex toLshIndex(ObjectProvider<StringRedisTemplate> redis) {
        StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
        return template == null ? null : new RedisLshIndex(template);
    }

    /**
     * 对指定文档的 active chunk 做近重复检测与分流。
     * 交换激活与 chunk 状态修改在调用方事务内生效（embedAndPersistStep 开头调用）。
     *
     * @return 本次处理的近重复对数（自动合并数 + 新写冲突单数；pairKey 去重跳过的不计）
     */
    public int inspect(String docId) {
        WikiAgentProperties.Dedup dedup = props.dedup();
        if (dedup == null || !dedup.enabled()) {
            return 0;
        }
        if (lsh == null) {
            log.warn("L1 近重复检测降级：Redis 缺席（wikiagent.redis.enabled=false？），跳过 docId={}", docId);
            return 0;
        }
        List<KbChildChunk> chunks = childRepo.findByDocIdAndActiveTrue(docId);
        if (chunks.isEmpty()) {
            return 0;
        }
        double threshold = dedup.nearJaccard();
        Map<String, long[]> sigByChunkId = new HashMap<>();
        Map<String, long[]> candidateSigCache = new HashMap<>();
        Set<String> existingPairs = null;  // 懒加载：首次写冲突单前才读全量对
        KbDocument newDoc = null;          // 懒加载：首次合并裁决前才读
        int pairs = 0;

        for (KbChildChunk fresh : chunks) {
            long[] sig = MinHashSignature.of(fresh.getContent());
            if (sig.length == 0) {
                continue;  // 超短 chunk（bigram 为空）跳过 LSH，不报错
            }
            sigByChunkId.put(fresh.getId(), sig);
            Set<String> candidateIds = new HashSet<>(lsh.query(sig));
            candidateIds.remove(fresh.getId());
            if (candidateIds.isEmpty()) {
                continue;
            }
            for (KbChildChunk candidate : childRepo.findByIdIn(candidateIds)) {
                if (!fresh.isActive()) {
                    break;  // fresh 已被前序合并下线，不再参与后续比对
                }
                if (!candidate.isActive() || docId.equals(candidate.getDocId())) {
                    continue;  // 已被下线 / 同文档命中跳过
                }
                long[] candidateSig = candidateSigCache.computeIfAbsent(candidate.getId(),
                        id -> MinHashSignature.of(candidate.getContent()));
                double sim = MinHashSignature.jaccard(sig, candidateSig);
                if (sim < threshold) {
                    continue;  // 桶内碰撞但精确 jaccard 未达阈值，不认定
                }
                if (KeyTokenDiffer.hasCriticalDiff(candidate.getContent(), fresh.getContent())) {
                    // 关键 token 差异 → 疑似冲突：双方不动，写冲突单（pairKey 无序对去重）
                    if (existingPairs == null) {
                        existingPairs = loadExistingPairs();
                    }
                    if (existingPairs.add(pairKey(candidate.getId(), fresh.getId()))) {
                        saveConflict(candidate, fresh, sim);
                        pairs++;
                    }
                } else {
                    // 非关键差异 → 自动合并：生效日晚者留 active，相同/缺失留旧（与 L0 一致）
                    if (newDoc == null) {
                        newDoc = docRepo.findById(docId).orElse(null);
                    }
                    pairs += merge(candidate, fresh, newDoc, sim);
                }
            }
        }

        // 存活 active chunk 的签名入 LSH（被合并下线者不入桶）
        for (KbChildChunk chunk : chunks) {
            if (!chunk.isActive()) {
                continue;
            }
            long[] sig = sigByChunkId.get(chunk.getId());
            if (sig != null) {
                lsh.put(chunk.getId(), sig);
            }
        }
        return pairs;
    }

    /**
     * 自动合并：生效日晚者留 active，早者软删（is_active=false）并从 LSH 移除；
     * 打 DEDUP_NEAR_MERGED metric（chunkId=输家）。相同/缺失生效日留旧（candidate 侧）。
     */
    private int merge(KbChildChunk candidate, KbChildChunk fresh, KbDocument newDoc, double sim) {
        KbDocument candidateDoc = docRepo.findById(candidate.getDocId()).orElse(null);
        LocalDate newDate = newDoc == null ? null : newDoc.getEffectiveDate();
        LocalDate oldDate = candidateDoc == null ? null : candidateDoc.getEffectiveDate();
        boolean newWins = newDate != null && oldDate != null && newDate.isAfter(oldDate);
        KbChildChunk loser = newWins ? candidate : fresh;
        KbChildChunk winner = newWins ? fresh : candidate;
        loser.setActive(false);
        childRepo.save(loser);
        lsh.remove(loser.getId(), MinHashSignature.of(loser.getContent()));
        MetricEventEntity metric = new MetricEventEntity();
        metric.setChunkId(loser.getId());
        metric.setEventType(EVENT_DEDUP_NEAR_MERGED);
        metric.setSimilarity(sim);
        metricDao.save(metric);
        log.info("L1 近重复自动合并：输家下线 loserChunk={} winnerChunk={} sim={}",
                loser.getId(), winner.getId(), String.format("%.4f", sim));
        return 1;
    }

    /** 写冲突单（status=DETECTED, source=MINHASH_INGEST, hint=CONFLICT）；chunkIdA=已存在侧，B=新块。 */
    private void saveConflict(KbChildChunk candidate, KbChildChunk fresh, double sim) {
        ConflictResolutionEntity e = new ConflictResolutionEntity();
        e.setChunkIdA(candidate.getId());
        e.setChunkIdB(fresh.getId());
        e.setSimilarity(sim);
        e.setStatus("DETECTED");
        e.setSource(CONFLICT_SOURCE);
        e.setResolutionHint(HINT_CONFLICT);
        conflictDao.save(e);
        log.info("L1 近重复关键 token 差异，写冲突单: {} ~ {} sim={}",
                candidate.getId(), fresh.getId(), String.format("%.4f", sim));
    }

    /** 已存在冲突的 chunk 对（任意状态），避免重复报单（沿用 ConflictDetectionService 思路）。 */
    private Set<String> loadExistingPairs() {
        Set<String> pairs = new HashSet<>();
        for (ConflictResolutionEntity e : conflictDao.findAll()) {
            pairs.add(pairKey(e.getChunkIdA(), e.getChunkIdB()));
        }
        return pairs;
    }

    /** 无序 pair key（a~b 与 b~a 等价）。 */
    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
