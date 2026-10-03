package com.wikiagent.application.knowledge;

import com.wikiagent.application.knowledge.dedup.KeyTokenDiffer;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.infrastructure.persistence.ConflictResolutionEntity;
import com.wikiagent.infrastructure.persistence.ConflictResolutionJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v4 §6.6.3 知识冲突检测服务。
 * <p>
 * 每日定时（{@code wikiagent.conflict.scan-cron}，默认 02:30）扫描已入库的 active 知识：
 * <ol>
 *   <li>按 (domainTag, subDomainTag) 垂直分组——冲突只可能发生在同一知识领域内</li>
 *   <li>组内对子 chunk 计算 embedding 余弦相似度（代码冲突解决页面的相似度依据）</li>
 *   <li>相似度 &gt; {@code wikiagent.conflict.threshold}（默认 0.8）且该 chunk 对
 *       未存在任何状态的冲突记录时，写入 conflict_resolution（status=DETECTED,
 *       source=SCHEDULED_SCAN）；resolution_hint 按 {@link KeyTokenDiffer} 分流：
 *       关键 token 差异 → CONFLICT，仅虚词差异 → REDUNDANT</li>
 * </ol>
 * <p>
 * <b>诚实声明 / 已知限制</b>：
 * <ul>
 *   <li>无 DASHSCOPE_API_KEY 时 EmbeddingModel 为 NoOp（全 0 向量），零向量余弦无意义，
 *       本服务自动跳过检测并在日志中说明，不会产生误报；配置 Key 后功能生效</li>
 *   <li>O(n²) 组内配对，单组超过 {@link #MAX_GROUP_SIZE} 时截断并告警（生产可改 Milvus
 *       向量近邻搜索实现，当前方案在知识库规模内可用）</li>
 * </ul>
 */
@Service
public class ConflictDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ConflictDetectionService.class);

    /** 单个领域分组参与配对的 chunk 数上限，防止 O(n²) 失控。 */
    private static final int MAX_GROUP_SIZE = 500;

    /** 向量化单批条数（DashScope 批量上限 10）。 */
    private static final int EMBED_BATCH = 10;

    /** conflict_resolution.source：离线定时扫描检出（与 ONLINE_GUARD / MINHASH_INGEST 区分）。 */
    public static final String SCAN_SOURCE = "SCHEDULED_SCAN";

    /** resolution_hint：diff 区间命中关键 token（数字/日期/货币/否定词）→ 建议按冲突裁决。 */
    public static final String HINT_CONFLICT = "CONFLICT";

    /** resolution_hint：仅虚词差异 → 建议按冗余合并而非冲突裁决。 */
    public static final String HINT_REDUNDANT = "REDUNDANT";

    private final KnowledgeMetadataJpaDao metadataDao;
    private final KbChildChunkRepo childRepo;
    private final ConflictResolutionJpaDao conflictDao;
    private final EmbeddingModel embeddingModel;
    private final double threshold;

    public ConflictDetectionService(KnowledgeMetadataJpaDao metadataDao,
                                    KbChildChunkRepo childRepo,
                                    ConflictResolutionJpaDao conflictDao,
                                    EmbeddingModel embeddingModel,
                                    @Value("${wikiagent.conflict.threshold:0.8}") double threshold) {
        this.metadataDao = metadataDao;
        this.childRepo = childRepo;
        this.conflictDao = conflictDao;
        this.embeddingModel = embeddingModel;
        this.threshold = threshold;
    }

    /** 定时入口：每日扫描。 */
    @Scheduled(cron = "${wikiagent.conflict.scan-cron:0 30 2 * * ?}")
    public void scheduledScan() {
        int found = scan();
        log.info("冲突定时扫描完成，新增 {} 对疑似冲突", found);
    }

    /**
     * 手动 / 定时统一入口：扫描全部 active 知识。
     *
     * @return 本次新写入的 DETECTED 冲突记录数
     */
    @Transactional
    public int scan() {
        List<KnowledgeMetadataEntity> active = metadataDao.findAllActive();
        if (active.size() < 2) {
            return 0;
        }

        // chunkId → 元数据
        Map<String, KnowledgeMetadataEntity> metaByChunk = new HashMap<>(active.size());
        // 分组 key "domain:subDomain" → chunkId 列表
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (KnowledgeMetadataEntity m : active) {
            metaByChunk.put(m.getChunkId(), m);
            groups.computeIfAbsent(m.getDomainTag() + ":" + m.getSubDomainTag(), k -> new ArrayList<>())
                    .add(m.getChunkId());
        }

        // 加载 chunk 内容（仅一次）
        Map<String, KbChildChunk> chunkById = loadChunks(metaByChunk.keySet());
        if (chunkById.isEmpty()) {
            log.warn("冲突扫描：knowledge_metadata 有 {} 行但 kb_child_chunk 无对应内容，跳过", active.size());
            return 0;
        }

        // 已存在冲突的 chunk 对（任意状态），避免重复报单
        Set<String> existingPairs = loadExistingPairs();

        int detected = 0;
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            List<String> ids = group.getValue();
            if (ids.size() < 2) {
                continue;
            }
            if (ids.size() > MAX_GROUP_SIZE) {
                log.warn("分组 {} chunk 数={} 超过上限 {}，截断扫描（建议改用 Milvus 近邻搜索）",
                        group.getKey(), ids.size(), MAX_GROUP_SIZE);
                ids = ids.subList(0, MAX_GROUP_SIZE);
            }
            detected += scanGroup(group.getKey(), ids, chunkById, metaByChunk, existingPairs);
        }
        return detected;
    }

    private int scanGroup(String groupKey, List<String> ids,
                          Map<String, KbChildChunk> chunkById,
                          Map<String, KnowledgeMetadataEntity> metaByChunk,
                          Set<String> existingPairs) {
        // 组内批量向量化
        Map<String, float[]> vectors = new HashMap<>(ids.size());
        List<String> texts = new ArrayList<>(ids.size());
        List<String> orderedIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            KbChildChunk c = chunkById.get(id);
            if (c != null && c.getContent() != null && !c.getContent().isBlank()) {
                texts.add(c.getContent());
                orderedIds.add(id);
            }
        }
        for (int i = 0; i < texts.size(); i += EMBED_BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + EMBED_BATCH, texts.size()));
            List<float[]> vecs = embeddingModel.embed(batch);
            for (int j = 0; j < vecs.size(); j++) {
                vectors.put(orderedIds.get(i + j), vecs.get(j));
            }
        }

        // NoOp EmbeddingModel 检测：全 0 向量 → 余弦无意义，跳过该组
        for (float[] v : vectors.values()) {
            if (norm(v) == 0.0) {
                log.warn("冲突扫描分组 {} 检测到零向量（DASHSCOPE_API_KEY 未配置？），跳过", groupKey);
                return 0;
            }
        }

        int detected = 0;
        for (int i = 0; i < orderedIds.size(); i++) {
            for (int j = i + 1; j < orderedIds.size(); j++) {
                String a = orderedIds.get(i);
                String b = orderedIds.get(j);
                double sim = cosine(vectors.get(a), vectors.get(b));
                if (sim > threshold) {
                    String pairKey = pairKey(a, b);
                    if (existingPairs.contains(pairKey)) {
                        continue;
                    }
                    saveConflict(metaByChunk.get(a), metaByChunk.get(b), a, b, sim, chunkById);
                    existingPairs.add(pairKey);
                    detected++;
                }
            }
        }
        return detected;
    }

    /**
     * 写 DETECTED 冲突单：source=SCHEDULED_SCAN；resolution_hint 由 {@link KeyTokenDiffer}
     * 对两侧正文 diff 区间判定——关键 token 差异 → CONFLICT，仅虚词差异 → REDUNDANT。
     */
    private void saveConflict(KnowledgeMetadataEntity ma, KnowledgeMetadataEntity mb,
                              String a, String b, double sim, Map<String, KbChildChunk> chunkById) {
        ConflictResolutionEntity e = new ConflictResolutionEntity();
        e.setChunkIdA(a);
        e.setChunkIdB(b);
        e.setSimilarity(sim);
        e.setDomainTag(ma != null ? ma.getDomainTag() : null);
        e.setSubDomainTag(ma != null ? ma.getSubDomainTag() : null);
        e.setStatus("DETECTED");
        e.setSource(SCAN_SOURCE);
        String textA = contentOf(chunkById, a);
        String textB = contentOf(chunkById, b);
        e.setResolutionHint(KeyTokenDiffer.hasCriticalDiff(textA, textB) ? HINT_CONFLICT : HINT_REDUNDANT);
        conflictDao.save(e);
        log.info("检测到知识冲突: {} ~ {} sim={} hint={} domain={}/{}",
                a, b, String.format("%.4f", sim), e.getResolutionHint(), e.getDomainTag(), e.getSubDomainTag());
    }

    private static String contentOf(Map<String, KbChildChunk> chunkById, String chunkId) {
        KbChildChunk c = chunkById.get(chunkId);
        return c == null ? null : c.getContent();
    }

    private Map<String, KbChildChunk> loadChunks(Set<String> chunkIds) {
        List<KbChildChunk> chunks = childRepo.findByIdIn(chunkIds);
        Map<String, KbChildChunk> map = new HashMap<>(chunks.size());
        for (KbChildChunk c : chunks) {
            map.put(c.getId(), c);
        }
        return map;
    }

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

    private static double cosine(float[] x, float[] y) {
        if (x == null || y == null || x.length != y.length) {
            return 0.0;
        }
        double dot = 0;
        double nx = 0;
        double ny = 0;
        for (int k = 0; k < x.length; k++) {
            dot += x[k] * y[k];
            nx += x[k] * x[k];
            ny += y[k] * y[k];
        }
        if (nx == 0 || ny == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(nx) * Math.sqrt(ny));
    }

    private static double norm(float[] v) {
        double n = 0;
        for (float f : v) {
            n += f * f;
        }
        return Math.sqrt(n);
    }
}
