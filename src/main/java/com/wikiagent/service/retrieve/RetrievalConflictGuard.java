package com.wikiagent.service.retrieve;

import com.wikiagent.application.gray.GrayReleaseService;
import com.wikiagent.application.ragcache.EmbeddingCacheService;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.retrieve.RetrievalSecurityContext;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.infrastructure.persistence.ConflictResolutionEntity;
import com.wikiagent.infrastructure.persistence.ConflictResolutionJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.service.retrieve.ConflictCandidateDetector.RepChunkView;
import com.wikiagent.service.retrieve.ConflictLlmJudge.ConflictVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Task 7 检索侧冲突守卫：粗筛（{@link ConflictCandidateDetector}）→ qwen-flash 精判
 * （{@link ConflictLlmJudge}）→ 系统按 kb_document.effective_date 确定性裁决。
 * <p>
 * 裁决规则（LLM 只检测不裁决，选边完全是系统侧确定性比较）：
 * <ul>
 *   <li>冲突组内生效时间唯一最晚者胜，其余 chunk 从本次候选移除
 *       （不打 DB、不动 Milvus，仅检索期过滤；审计靠日志 + metric_event + conflict_resolution）</li>
 *   <li>生效时间相同 / 任一方缺失（存量老数据）/ 组内并列最晚 → 整组保留 + conflictNote 标注，
 *       不默认选边（Review Focus #1/#4）</li>
 *   <li>传递冲突组（A~B、B~C）按并查集并为一组统一裁决</li>
 * </ul>
 * 门控：{@code wikiagent.conflict.guard.enabled}（默认 false）且灰度特性
 * {@code conflict-guard} 命中当前请求身份才生效；否则原样透传零副作用。
 * <p>
 * <b>诚实声明 / fail-open</b>：守卫内部任何异常（embed 失败、DAO 异常等）一律
 * 告警并原样透传，绝不阻断问答主链路；精判本身的异常语义由
 * {@link ConflictLlmJudge} 保证（视为无冲突）。向量来源：Milvus Hit 不带 dense 向量，
 * 这里对代表子块正文重 embed，并优先走 {@link EmbeddingCacheService} 精确缓存摊薄成本。
 */
@Component
public class RetrievalConflictGuard {

    private static final Logger log = LoggerFactory.getLogger(RetrievalConflictGuard.class);

    /** metric_event：检出冲突（按 chunk 各记一条，chunkId 为真实冲突方）。 */
    public static final String EVENT_DETECTED = "CONFLICT_GUARD_DETECTED";
    /** metric_event：输家 chunk 被本次检索移除（chunkId=输家）。 */
    public static final String EVENT_REMOVED = "CONFLICT_GUARD_REMOVED";
    /** metric_event：生效时间相同/缺失/并列最晚，整组保留（chunkId=保留方）。 */
    public static final String EVENT_KEPT_BOTH = "CONFLICT_GUARD_KEPT_BOTH";
    /** conflict_resolution.source：在线检索守卫检出。 */
    public static final String CONFLICT_SOURCE = "ONLINE_GUARD";
    /** 灰度特性名（wikiagent.gray.features.conflict-guard）。 */
    public static final String GRAY_FEATURE = "conflict-guard";

    /**
     * 守卫结果。
     *
     * @param kept         裁决后保留的候选（保持入参顺序；透传时为原列表）
     * @param conflictNote 冲突标注段（nullable；仅在"保留但存在冲突"时生成，由调用方拼进 ctx 末尾）
     * @param verdicts     LLM 精判确认的冲突裁决列表（无冲突/未判定时为空）
     */
    public record GuardResult(List<RepChunkView> kept, String conflictNote, List<ConflictVerdict> verdicts) {
    }

    private final WikiAgentProperties props;
    private final KbDocumentRepo docRepo;
    private final MetricEventJpaDao metricDao;
    private final ConflictResolutionJpaDao conflictDao;
    private final EmbeddingModel embeddingModel;
    /** embedding 精确缓存（可选，缺席时直调模型）。 */
    private final EmbeddingCacheService embeddingCache;
    /** LLM 精判（可选；bean 仅在 wikiagent.conflict.guard.enabled=true 时装配）。 */
    private final ConflictLlmJudge judge;
    /** 灰度决策（可选，缺失时不门控）。 */
    private final GrayReleaseService grayRelease;
    /** 缓存 key 中的模型名，与 DashScope embedding 配置一致。 */
    private final String embeddingModelName;
    private final ConflictCandidateDetector detector = new ConflictCandidateDetector();

    public RetrievalConflictGuard(WikiAgentProperties props,
                                  KbDocumentRepo docRepo,
                                  MetricEventJpaDao metricDao,
                                  ConflictResolutionJpaDao conflictDao,
                                  EmbeddingModel embeddingModel,
                                  ObjectProvider<EmbeddingCacheService> embeddingCache,
                                  ObjectProvider<ConflictLlmJudge> judge,
                                  ObjectProvider<GrayReleaseService> grayRelease,
                                  @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v4}")
                                  String embeddingModelName) {
        this.props = props;
        this.docRepo = docRepo;
        this.metricDao = metricDao;
        this.conflictDao = conflictDao;
        this.embeddingModel = embeddingModel;
        this.embeddingCache = embeddingCache == null ? null : embeddingCache.getIfAvailable();
        this.judge = judge == null ? null : judge.getIfAvailable();
        this.grayRelease = grayRelease == null ? null : grayRelease.getIfAvailable();
        this.embeddingModelName = embeddingModelName;
    }

    /**
     * 对检索候选的代表子块做冲突裁决。
     *
     * @param query      用户原始 query（精判 prompt 用）
     * @param candidates 候选代表子块（与最终进入组装的父块一一对应）
     * @return 裁决结果；任何关闭/未命中/异常路径均原样透传
     */
    public GuardResult apply(String query, List<RepChunkView> candidates) {
        WikiAgentProperties.ConflictGuard cfg = props.conflictGuard();
        if (cfg == null || !cfg.enabled()) {
            return passthrough(candidates);
        }
        if (grayRelease != null
                && !grayRelease.isEnabled(GRAY_FEATURE, RetrievalSecurityContext.currentIdentity())) {
            return passthrough(candidates);
        }
        if (judge == null || candidates == null || candidates.size() < 2
                || query == null || query.isBlank()) {
            return passthrough(candidates);
        }
        try {
            return doApply(query, candidates, cfg.coarseCosine());
        } catch (Exception e) {
            // fail-open：守卫异常绝不阻断问答主链路
            log.warn("ConflictGuard 裁决异常，原样透传候选: {}", e.getMessage());
            return passthrough(candidates);
        }
    }

    private GuardResult doApply(String query, List<RepChunkView> candidates, double coarseCosine) {
        // 粗筛：重 embed 代表子块（优先精确缓存），O(k²) 余弦 + 关键 token 复判
        List<String> texts = candidates.stream().map(RepChunkView::content).toList();
        List<float[]> vectors = embeddingCache == null
                ? embeddingModel.embed(texts)
                : embeddingCache.embedAll(embeddingModelName, texts, embeddingModel::embed);
        if (vectors == null || vectors.size() != candidates.size() || vectors.stream().anyMatch(v -> v == null)) {
            log.warn("ConflictGuard 向量缺失（数量不齐/含 null），跳过粗筛");
            return passthrough(candidates);
        }
        List<int[]> pairs = detector.detect(candidates, vectors.toArray(new float[0][]), coarseCosine);
        if (pairs.isEmpty()) {
            return passthrough(candidates);
        }

        // 精判：LLM 只检测不裁决；判不上的对按无冲突放行（judge 自身 fail-open）
        List<ConflictVerdict> verdicts = new ArrayList<>();
        Map<String, Double> pairSim = new HashMap<>();
        for (int[] pair : pairs) {
            RepChunkView a = candidates.get(pair[0]);
            RepChunkView b = candidates.get(pair[1]);
            Optional<ConflictVerdict> verdict = judge.judge(query, List.of(a, b));
            verdict.ifPresent(v -> {
                verdicts.add(v);
                pairSim.put(pairKey(v.chunkIdA(), v.chunkIdB()), cosine(vectors.get(pair[0]), vectors.get(pair[1])));
            });
        }
        if (verdicts.isEmpty()) {
            return new GuardResult(candidates, null, List.of());
        }

        // 审计：每个冲突对写 DETECTED 冲突单（pairKey 无序对已存在任意状态则不重复写）+ DETECTED 打点
        Set<String> existingPairs = loadExistingPairs();
        for (ConflictVerdict v : verdicts) {
            if (existingPairs.add(pairKey(v.chunkIdA(), v.chunkIdB()))) {
                saveConflict(v, pairSim.getOrDefault(pairKey(v.chunkIdA(), v.chunkIdB()), 0.0));
            }
            saveMetric(v.chunkIdA(), EVENT_DETECTED);
            saveMetric(v.chunkIdB(), EVENT_DETECTED);
        }

        // 并查集：按冲突关系把 chunk 并成组（传递冲突组统一裁决）
        Map<String, String> parent = new HashMap<>();
        for (ConflictVerdict v : verdicts) {
            union(parent, v.chunkIdA(), v.chunkIdB());
        }
        Map<String, List<RepChunkView>> groups = new LinkedHashMap<>();
        for (RepChunkView c : candidates) {
            if (parent.containsKey(c.childId())) {
                groups.computeIfAbsent(find(parent, c.childId()), k -> new ArrayList<>()).add(c);
            }
        }

        // 裁决：组内生效时间唯一最晚者胜；相同/缺失/并列最晚 → 整组保留 + 标注
        Map<String, String> chunkToDoc = new LinkedHashMap<>();
        for (RepChunkView c : candidates) {
            chunkToDoc.put(c.childId(), c.docId());
        }
        Map<String, KbDocument> docs = loadDocs(chunkToDoc);
        Set<String> removed = new HashSet<>();
        StringBuilder note = new StringBuilder();
        for (List<RepChunkView> group : groups.values()) {
            arbitrate(group, docs, chunkToDoc, verdicts, removed, note);
        }

        List<RepChunkView> kept = new ArrayList<>(candidates.size());
        for (RepChunkView c : candidates) {
            if (!removed.contains(c.childId())) {
                kept.add(c);
            }
        }
        String conflictNote = note.length() == 0 ? null : note.toString();
        return new GuardResult(kept, conflictNote, List.copyOf(verdicts));
    }

    /** 组内裁决：唯一最晚 → 其余移除；否则整组保留 + 标注。 */
    private void arbitrate(List<RepChunkView> group, Map<String, KbDocument> docs,
                           Map<String, String> chunkToDoc, List<ConflictVerdict> verdicts,
                           Set<String> removed, StringBuilder note) {
        Map<String, LocalDate> dates = new HashMap<>();
        boolean anyNull = false;
        LocalDate max = null;
        for (RepChunkView c : group) {
            KbDocument doc = docs.get(c.docId());
            LocalDate d = doc == null ? null : doc.getEffectiveDate();
            dates.put(c.childId(), d);
            if (d == null) {
                anyNull = true;
            } else if (max == null || d.isAfter(max)) {
                max = d;
            }
        }
        if (!anyNull) {
            List<RepChunkView> winners = new ArrayList<>();
            for (RepChunkView c : group) {
                if (dates.get(c.childId()).equals(max)) {
                    winners.add(c);
                }
            }
            if (winners.size() == 1) {
                String winnerId = winners.get(0).childId();
                for (RepChunkView c : group) {
                    if (!c.childId().equals(winnerId)) {
                        removed.add(c.childId());
                        saveMetric(c.childId(), EVENT_REMOVED);
                        log.info("ConflictGuard 移除输家 chunk：loser={} winner={} loserEff={} winnerEff={}",
                                c.childId(), winnerId, dates.get(c.childId()), max);
                    }
                }
                return;
            }
        }
        // 生效时间相同 / 一方缺失 / 并列最晚 → 整组保留，不默认选边
        for (RepChunkView c : group) {
            saveMetric(c.childId(), EVENT_KEPT_BOTH);
        }
        log.info("ConflictGuard 冲突组整组保留（不自动选边）: chunks={}",
                group.stream().map(RepChunkView::childId).toList());
        for (ConflictVerdict v : verdicts) {
            if (contains(group, v.chunkIdA()) && contains(group, v.chunkIdB())) {
                note.append(conflictLine(v, docs, chunkToDoc, dates));
            }
        }
    }

    /** 冲突标注行："来源A（生效于X）与来源B（生效于Y）冲突：…"，日期缺失标"未知"。 */
    private String conflictLine(ConflictVerdict v, Map<String, KbDocument> docs,
                                Map<String, String> chunkToDoc, Map<String, LocalDate> dates) {
        String docIdA = chunkToDoc.getOrDefault(v.chunkIdA(), v.chunkIdA());
        String docIdB = chunkToDoc.getOrDefault(v.chunkIdB(), v.chunkIdB());
        return "【冲突提示】来源" + filenameOf(docs, docIdA)
                + "（生效于" + dateText(dates.get(v.chunkIdA())) + "）"
                + "与来源" + filenameOf(docs, docIdB)
                + "（生效于" + dateText(dates.get(v.chunkIdB())) + "）"
                + "冲突：" + nullSafe(v.entity()) + "的" + nullSafe(v.attribute())
                + "分别为「" + nullSafe(v.valueA()) + "」和「" + nullSafe(v.valueB())
                + "」，系统未自动选边，请人工核对。\n";
    }

    private String filenameOf(Map<String, KbDocument> docs, String docId) {
        KbDocument d = docs.get(docId);
        return d == null || d.getFilename() == null ? docId : d.getFilename();
    }

    private static String dateText(LocalDate d) {
        return d == null ? "未知" : d.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "?" : s;
    }

    private static boolean contains(List<RepChunkView> group, String childId) {
        return group.stream().anyMatch(c -> c.childId().equals(childId));
    }

    private Map<String, KbDocument> loadDocs(Map<String, String> chunkToDoc) {
        Set<String> docIds = new HashSet<>(chunkToDoc.values());
        Map<String, KbDocument> docs = new HashMap<>();
        for (KbDocument d : docRepo.findAllById(docIds)) {
            docs.put(d.getId(), d);
        }
        return docs;
    }

    /** 写冲突单（status=DETECTED, source=ONLINE_GUARD, hint=CONFLICT）；chunkIdA/B=冲突双方。 */
    private void saveConflict(ConflictVerdict v, double similarity) {
        ConflictResolutionEntity e = new ConflictResolutionEntity();
        e.setChunkIdA(v.chunkIdA());
        e.setChunkIdB(v.chunkIdB());
        e.setSimilarity(similarity);
        e.setStatus("DETECTED");
        e.setSource(CONFLICT_SOURCE);
        e.setResolutionHint("CONFLICT");
        conflictDao.save(e);
        log.info("ConflictGuard 检出冲突，写冲突单: {} ~ {} sim={}",
                v.chunkIdA(), v.chunkIdB(), String.format("%.4f", similarity));
    }

    private void saveMetric(String chunkId, String eventType) {
        MetricEventEntity e = new MetricEventEntity();
        e.setChunkId(chunkId);
        e.setEventType(eventType);
        metricDao.save(e);
    }

    /** 已存在冲突的 chunk 对（任意状态），避免重复报单（与 NearDuplicateGuard 同口径）。 */
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

    private static GuardResult passthrough(List<RepChunkView> candidates) {
        return new GuardResult(candidates, null, List.of());
    }

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (!root.equals(parent.get(root))) {
            root = parent.get(root);
        }
        // 路径压缩
        String cur = x;
        while (!cur.equals(root)) {
            String next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        parent.putIfAbsent(a, a);
        parent.putIfAbsent(b, b);
        parent.put(find(parent, a), find(parent, b));
    }

    /** 余弦相似度（写冲突单 similarity 字段用；与 ConflictCandidateDetector 同算法）。 */
    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
