package com.wikiagent.service.retrieve;

import com.wikiagent.application.gray.GrayReleaseService;
import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.spi.RerankProvider;
import com.wikiagent.domain.llm.spi.RerankRequest;
import com.wikiagent.domain.llm.spi.RerankResult;
import com.wikiagent.domain.retrieve.RetrievalFilter;
import com.wikiagent.domain.retrieve.RetrievalQuery;
import com.wikiagent.domain.retrieve.RetrievalSecurityContext;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.store.MilvusStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：查询向量化 → Milvus 双路召回（BM25 + 向量，服务端 RRF 融合）
 * → top N 子块替换为父文档（按命中顺序去重、字符预算内组装上下文）。
 *
 * 通过 Accumulator 支持多轮检索累积：跨轮按父块去重（保留最高分与首次命中顺序），
 * 最终统一组装，供 Agentic RAG 迭代式检索使用。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** Milvus 故障后的本地降级冷却时长（毫秒），冷却期内直接走 DB 关键词检索，避免每次 10s 超时。 */
    private static final long FALLBACK_COOLDOWN_MS = 60_000L;
    /** 本地降级：每个查询最多抽取的 bigram/关键词数量。 */
    private static final int FALLBACK_MAX_TERMS = 12;

    /**
     * 引用来源（§2.5 六字段 + filename）。
     * <ul>
     *   <li>{@code versionNo}/{@code pageNo}：取该父块代表子块（父块下最高分、平局取 childIndex 小者）
     *       的实体值；代表子块行缺失时 versionNo=0、pageNo=null</li>
     *   <li>{@code snippet}：代表子块 content 去空白后前 200 字符</li>
     *   <li>{@code artifactId}：由 knowledge_metadata.artifact_id（V11）按代表子块回填；
     *       未打标的公共知识为 null</li>
     *   <li>{@code filename}：保留在末尾，JSON 仍含 filename 以兼容现有前端</li>
     * </ul>
     * 序列化 JSON 字段名保持驼峰（index/docId/versionNo/pageNo/snippet/artifactId/score/filename）。
     */
    public record Source(int index, String docId, int versionNo, Integer pageNo, String snippet,
                         String artifactId, double score, String filename) {
    }

    /** context 为空表示知识库中没有检索到相关内容。 */
    public record RetrievalResult(List<Source> sources, String context) {
    }

    /** 多轮检索累积器：按父块 ID 去重的命中集合。 */
    public static final class Accumulator {
        private final LinkedHashSet<String> parentOrder = new LinkedHashSet<>();
        private final Map<String, Double> bestScore = new HashMap<>();
        private final Map<String, String> parentDoc = new HashMap<>();
        /** 每个父块的代表子块（§2.5：同父块得分最高；平局取 childIndex 小者）。 */
        private final Map<String, RepChild> repChild = new HashMap<>();

        /** 代表子块引用：childId + childIndex + 该子块自身命中分。 */
        record RepChild(String childId, int childIndex, double score) {
        }

        /** 测试辅助：直接放入一条命中（同包可见，无代表子块，引用六字段回退默认值）。 */
        void put(String parentId, String docId, double score) {
            parentOrder.add(parentId);
            bestScore.merge(parentId, score, Math::max);
            parentDoc.putIfAbsent(parentId, docId);
        }

        /** 累积一条带子块的命中，并维护父块代表子块。 */
        void put(String parentId, String docId, String childId, int childIndex, double score) {
            put(parentId, docId, score);
            noteChild(parentId, childId, childIndex, score);
        }

        /** 按"得分最高、平局 childIndex 最小"更新代表子块。 */
        void noteChild(String parentId, String childId, int childIndex, double score) {
            if (childId == null) {
                return;
            }
            RepChild cur = repChild.get(parentId);
            if (cur == null || score > cur.score() || (score == cur.score() && childIndex < cur.childIndex())) {
                repChild.put(parentId, new RepChild(childId, childIndex, score));
            }
        }
    }

    private final WikiAgentProperties props;
    private final MilvusStoreService milvus;
    private final EmbeddingModel embeddingModel;
    private final KbParentChunkRepo parentRepo;
    private final KbDocumentRepo docRepo;
    private final KbChildChunkRepo childRepo;
    private final MetricEventJpaDao metricEventDao;
    /** E2：可选 rerank provider（不可用时为 null，检索按原序返回）。 */
    private final RerankProvider rerankProvider;
    /** E5：知识元数据 DAO（可选，权限过滤用；缺失时不过滤）。 */
    private final KnowledgeMetadataJpaDao metadataDao;
    /** E5：是否把权限表达式下推 Milvus（存量集合缺标量列时须保持 false）。 */
    private final boolean milvusFilterMetadata;
    /** E3：RERANK 打点器（可选，缺失时只重排不打点）。 */
    private final ModelCallRecorder callRecorder;
    /** 灰度发布决策（可选，缺失/未配置规则时不门控）。 */
    private final GrayReleaseService grayRelease;
    /** rerank 灰度特性名（wikiagent.gray.features.rerank）。 */
    private static final String GRAY_FEATURE_RERANK = "rerank";

    /** GraphRAG 服务（可选，wikiagent.graph.enabled=true 时注入）。 */
    private final com.wikiagent.application.graph.GraphRagService graphRagService;

    /** Task 7 检索侧冲突守卫（可选；内部按 wikiagent.conflict.guard.enabled + 灰度门控）。 */
    private final RetrievalConflictGuard conflictGuard;

    /** Milvus 不可用截止时间戳；0 表示正常，>now 表示冷却降级中。 */
    private volatile long milvusDisabledUntil = 0L;

    /** 兼容旧构造（测试/旧装配）：无 rerank provider、无权限过滤。 */
    public RetrievalService(WikiAgentProperties props, MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KbParentChunkRepo parentRepo, KbDocumentRepo docRepo, KbChildChunkRepo childRepo,
                            MetricEventJpaDao metricEventDao) {
        this(props, milvus, embeddingModel, parentRepo, docRepo, childRepo, metricEventDao, null, null, false,
                null, null, null);
    }

    /** E5 构造（兼容）：无 RERANK 打点器。 */
    public RetrievalService(WikiAgentProperties props, MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KbParentChunkRepo parentRepo, KbDocumentRepo docRepo, KbChildChunkRepo childRepo,
                            MetricEventJpaDao metricEventDao,
                            org.springframework.beans.factory.ObjectProvider<RerankProvider> rerankProvider,
                            org.springframework.beans.factory.ObjectProvider<KnowledgeMetadataJpaDao> metadataDao,
                            boolean milvusFilterMetadata) {
        this(props, milvus, embeddingModel, parentRepo, docRepo, childRepo, metricEventDao,
                rerankProvider, metadataDao, milvusFilterMetadata, null, null, null);
    }

    /**
     * E2/E3/E5 12 参构造（eval 套件兼容）：无 GraphRAG。
     */
    public RetrievalService(WikiAgentProperties props, MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KbParentChunkRepo parentRepo, KbDocumentRepo docRepo, KbChildChunkRepo childRepo,
                            MetricEventJpaDao metricEventDao,
                            org.springframework.beans.factory.ObjectProvider<RerankProvider> rerankProvider,
                            org.springframework.beans.factory.ObjectProvider<KnowledgeMetadataJpaDao> metadataDao,
                            boolean milvusFilterMetadata,
                            org.springframework.beans.factory.ObjectProvider<ModelCallRecorder> callRecorder,
                            org.springframework.beans.factory.ObjectProvider<GrayReleaseService> grayRelease) {
        this(props, milvus, embeddingModel, parentRepo, docRepo, childRepo, metricEventDao,
                rerankProvider, metadataDao, milvusFilterMetadata, callRecorder, grayRelease, null);
    }

    /**
     * E2/E3/E5 13 参构造（兼容）：无 ConflictGuard。
     */
    public RetrievalService(WikiAgentProperties props, MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KbParentChunkRepo parentRepo, KbDocumentRepo docRepo, KbChildChunkRepo childRepo,
                            MetricEventJpaDao metricEventDao,
                            org.springframework.beans.factory.ObjectProvider<RerankProvider> rerankProvider,
                            org.springframework.beans.factory.ObjectProvider<KnowledgeMetadataJpaDao> metadataDao,
                            boolean milvusFilterMetadata,
                            org.springframework.beans.factory.ObjectProvider<ModelCallRecorder> callRecorder,
                            org.springframework.beans.factory.ObjectProvider<GrayReleaseService> grayRelease,
                            org.springframework.beans.factory.ObjectProvider<com.wikiagent.application.graph.GraphRagService> graphRagService) {
        this(props, milvus, embeddingModel, parentRepo, docRepo, childRepo, metricEventDao,
                rerankProvider, metadataDao, milvusFilterMetadata, callRecorder, grayRelease,
                graphRagService, null);
    }

    /**
     * E2/E3/E5/Task7 全量装配构造。
     *
     * @param callRecorder RERANK 打点器（ObjectProvider 可选；无 Bean 时不打点）
     * @param grayRelease  灰度决策（ObjectProvider 可选；无 Bean 时不门控）
     * @param graphRagService GraphRAG 图检索（ObjectProvider 可选；wikiagent.graph.enabled=true 时存在）
     * @param conflictGuard 检索侧冲突守卫（ObjectProvider 可选；与 rerankProvider 同款可选注入模式）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalService(WikiAgentProperties props, MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KbParentChunkRepo parentRepo, KbDocumentRepo docRepo, KbChildChunkRepo childRepo,
                            MetricEventJpaDao metricEventDao,
                            org.springframework.beans.factory.ObjectProvider<RerankProvider> rerankProvider,
                            org.springframework.beans.factory.ObjectProvider<KnowledgeMetadataJpaDao> metadataDao,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${wikiagent.milvus.filter-metadata:false}") boolean milvusFilterMetadata,
                            org.springframework.beans.factory.ObjectProvider<ModelCallRecorder> callRecorder,
                            org.springframework.beans.factory.ObjectProvider<GrayReleaseService> grayRelease,
                            org.springframework.beans.factory.ObjectProvider<com.wikiagent.application.graph.GraphRagService> graphRagService,
                            org.springframework.beans.factory.ObjectProvider<RetrievalConflictGuard> conflictGuard) {
        this.props = props;
        this.milvus = milvus;
        this.embeddingModel = embeddingModel;
        this.parentRepo = parentRepo;
        this.docRepo = docRepo;
        this.childRepo = childRepo;
        this.metricEventDao = metricEventDao;
        RerankProvider rp = rerankProvider == null ? null : rerankProvider.getIfAvailable();
        this.rerankProvider = rp != null && rp.available() ? rp : null;
        this.metadataDao = metadataDao == null ? null : metadataDao.getIfAvailable();
        this.milvusFilterMetadata = milvusFilterMetadata;
        this.callRecorder = callRecorder == null ? null : callRecorder.getIfAvailable();
        this.grayRelease = grayRelease == null ? null : grayRelease.getIfAvailable();
        this.graphRagService = graphRagService == null ? null : graphRagService.getIfAvailable();
        this.conflictGuard = conflictGuard == null ? null : conflictGuard.getIfAvailable();
    }

    /** 单次多查询检索（一次组装，rerank 可用时重排）。 */
    public RetrievalResult retrieve(List<String> queries) {
        Accumulator acc = search(new Accumulator(), queries);
        graphExpand(acc, queries);
        String rerankQuery = firstNonBlank(queries);
        RetrievalResult result = assemble(acc, rerankQuery);
        recordRetrievalMetrics(result);
        return result;
    }

    /** 图扩展单轮最多注入的子块数：bigram 探针可能命中多个实体，设护栏防止关联 chunk 泛滥。 */
    private static final int GRAPH_MAX_INJECTED_CHUNKS = 20;

    /**
     * GraphRAG 图扩展（wikiagent.graph.enabled=true 时生效）：
     * 按查询匹配实体 → 1-hop 邻居扩展 → 关联 chunk 映射回父块累积，
     * 补充向量检索未覆盖的实体关联上下文。图检索失败不影响主流程。
     * <p>
     * 探针策略：① 整句精确匹配（用户直接输入实体名时最准）；
     * ② 多词自然语言按 bigram/拉丁词拆解后逐探针匹配（与本地关键词降级同口径），
     * 否则像「WMS 什么时候升级 3.0」这类改写查询永远无法命中名为「仓储管理系统 WMS」的实体。
     */
    public void graphExpand(Accumulator acc, List<String> queries) {
        if (graphRagService == null || queries == null) {
            return;
        }
        java.util.Set<String> injectedChunkIds = new java.util.HashSet<>();
        for (String q : queries) {
            if (q == null || q.isBlank()) {
                continue;
            }
            // 整句探针优先，bigram/拉丁词探针补充，去重避免同一实体重复查询
            LinkedHashSet<String> probes = new LinkedHashSet<>();
            probes.add(q.trim());
            probes.addAll(extractTerms(q));
            for (String probe : probes) {
                if (injectedChunkIds.size() >= GRAPH_MAX_INJECTED_CHUNKS) {
                    return;
                }
                try {
                    var graphResult = graphRagService.searchWithExpansion(probe);
                    if (graphResult == null || graphResult.relatedChunkIds().isEmpty()) {
                        continue;
                    }
                    for (KbChildChunk c : childRepo.findByIdIn(graphResult.relatedChunkIds())) {
                        if (c.isActive() && injectedChunkIds.add(c.getId())) {
                            // 图命中给一个中等置信分，避免压过向量高分，但能被 rerank 重排
                            acc.put(c.getParentId(), c.getDocId(), c.getId(), c.getChildIndex(), 0.5);
                            if (injectedChunkIds.size() >= GRAPH_MAX_INJECTED_CHUNKS) {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("GraphRAG 图扩展失败（不影响检索主流程）: {}", e.getMessage());
                }
            }
        }
    }

    /** 单查询单次检索（兼容旧链路）。 */
    public RetrievalResult retrieve(String query) {
        return retrieve(List.of(query));
    }

    /**
     * E5：带权限表达式的单查询检索。filterExpression 与当前请求身份
     * （X-Business-Identity → RetrievalSecurityContext）合并（AND）后生效。
     */
    public RetrievalResult retrieve(RetrievalQuery rq) {
        RetrievalFilter filter = effectiveFilter(rq == null ? null : rq.filterExpression());
        Accumulator acc = search(new Accumulator(),
                rq == null ? List.of() : List.of(rq.query()), filter);
        RetrievalResult result = assemble(acc, rq == null ? null : rq.query());
        recordRetrievalMetrics(result);
        return result;
    }

    /** 合并显式表达式与当前身份过滤。 */
    private RetrievalFilter effectiveFilter(String filterExpression) {
        return RetrievalFilter.parse(filterExpression).and(RetrievalSecurityContext.identityFilter());
    }

    private static String firstNonBlank(List<String> queries) {
        if (queries == null) return "";
        for (String q : queries) {
            if (q != null && !q.isBlank()) return q;
        }
        return "";
    }

    /**
     * v4 §6.6 指标埋点：最终进入生成上下文的来源各记 RETRIEVED + CITED 一条。
     * <p>
     * 如实声明两点粒度限制：
     * 1）Source 仅暴露 docId（无父块/子块 ID），metric_event.chunk_id 列暂存 docId 作为归属键，
     *    看板按总量聚合不受影响，单 chunk 明细回查不适用该来源；
     * 2）本管道"召回即引用"，RETRIEVED/CITED 计数相同；多轮 AgentRag 路径的候选/引用区分暂未埋点。
     * 埋点失败不得影响对话主链路。
     */
    public void recordRetrievalMetrics(RetrievalResult result) {
        if (result.sources().isEmpty()) {
            return;
        }
        try {
            for (Source s : result.sources()) {
                metricEventDao.save(newMetric(s, "RETRIEVED"));
                metricEventDao.save(newMetric(s, "CITED"));
            }
        } catch (Exception e) {
            log.warn("检索指标埋点失败（不影响对话）: {}", e.getMessage());
        }
    }

    private MetricEventEntity newMetric(Source s, String eventType) {
        MetricEventEntity e = new MetricEventEntity();
        e.setChunkId(s.docId());
        e.setEventType(eventType);
        e.setSimilarity(s.score());
        return e;
    }

    public Accumulator newAccumulator() {
        return new Accumulator();
    }

    /**
     * 执行一轮多查询混合检索并累积进 acc（空查询被忽略）。
     * 同一父块跨轮/跨查询只保留最高分与首次命中顺序。
     * 自动叠加当前请求身份过滤（RetrievalSecurityContext）。
     */
    public Accumulator search(Accumulator acc, List<String> queries) {
        return search(acc, queries, effectiveFilter(null));
    }

    /**
     * E5：带权限过滤的多查询检索。关系库侧按 knowledge_metadata 行做权威过滤
     * （未打标 chunk 默认放行）；wikiagent.milvus.filter-metadata=true 且集合含
     * 标量列时同时下推 Milvus 表达式。
     */
    public Accumulator search(Accumulator acc, List<String> queries, RetrievalFilter filter) {
        if (acc == null || queries == null) {
            return acc;
        }
        RetrievalFilter effective = filter == null ? RetrievalFilter.none() : filter;
        String pushdownExpr = milvusFilterMetadata && !effective.isEmpty() ? effective.toMilvusExpr() : null;
        boolean fallback = System.currentTimeMillis() < milvusDisabledUntil;
        for (String q : queries) {
            if (q == null || q.isBlank()) {
                continue;
            }
            if (fallback) {
                localKeywordSearch(acc, q, effective);
                continue;
            }
            try {
                float[] qvec = embeddingModel.embed(q);
                List<MilvusStoreService.Hit> hits = milvus.hybridSearch(
                        qvec, q,
                        props.retrieve().subTopk(), props.retrieve().finalTopk(), props.retrieve().rrfK(),
                        pushdownExpr);
                hits = filterHitsByMetadata(hits, effective);
                for (MilvusStoreService.Hit h : hits) {
                    // §2.5：累积命中同时记录父块代表子块（最高分、平局 childIndex 小者）
                    acc.put(h.parentId(), h.docId(), h.childId(), h.childIndex(), h.score());
                }
            } catch (Exception e) {
                // Milvus 不可用时降级为 DB 关键词检索（开发零依赖 / 生产故障兜底），冷却期内不再尝试 Milvus
                log.warn("Milvus 混合检索失败，降级为本地关键词检索: {}", e.getMessage());
                milvusDisabledUntil = System.currentTimeMillis() + FALLBACK_COOLDOWN_MS;
                fallback = true;
                localKeywordSearch(acc, q, effective);
            }
        }
        return acc;
    }

    /**
     * E5/E6：Milvus 命中的关系库权威过滤，<b>无论是否带 ACL 表达式始终执行</b>。
     * 关系库为权威源（Milvus 行不物理删除，冲突下线只翻关系库软删标志），双门控：
     * <ol>
     *   <li>子块门：{@code kb_child_chunk} 行不存在或 {@code active=false} → 剔除；</li>
     *   <li>元数据门：{@code knowledge_metadata} 行存在且 {@code isActive=false}
     *       （冲突 DELETE_A/DELETE_B 下线）→ 剔除；行不存在默认放行；</li>
     *   <li>ACL 门：权限表达式在上述基础上 AND 叠加。</li>
     * </ol>
     * 仅 DAO 查询异常时保守放行 + WARN；is_active 判定本身是内存布尔判断，不走异常分支。
     */
    private List<MilvusStoreService.Hit> filterHitsByMetadata(List<MilvusStoreService.Hit> hits,
                                                              RetrievalFilter filter) {
        if (hits == null || hits.isEmpty() || (childRepo == null && metadataDao == null)) {
            return hits;
        }
        List<String> childIds = hits.stream().map(MilvusStoreService.Hit::childId).toList();
        Map<String, KbChildChunk> childRows = null;
        Map<String, KnowledgeMetadataEntity> meta = null;
        try {
            if (childRepo != null) {
                childRows = new HashMap<>();
                for (KbChildChunk c : childRepo.findByIdIn(childIds)) {
                    childRows.put(c.getId(), c);
                }
            }
            if (metadataDao != null) {
                meta = loadMetadata(childIds);
            }
        } catch (Exception e) {
            log.warn("检索软删/权限过滤的关系库查询失败（保守放行原结果）: {}", e.getMessage());
            return hits;
        }
        List<MilvusStoreService.Hit> out = new ArrayList<>(hits.size());
        for (MilvusStoreService.Hit h : hits) {
            if (childRows != null) {
                KbChildChunk c = childRows.get(h.childId());
                if (c == null || !c.isActive()) {
                    continue; // 子块行不存在（关系库为权威）或子块已软删
                }
            }
            KnowledgeMetadataEntity m = meta == null ? null : meta.get(h.childId());
            if (m != null && Boolean.FALSE.equals(m.getIsActive())) {
                continue; // 冲突下线：元数据软删
            }
            if (!allowed(m, filter)) {
                continue; // ACL 表达式门（AND 叠加）
            }
            out.add(h);
        }
        return out;
    }

    private Map<String, KnowledgeMetadataEntity> loadMetadata(List<String> chunkIds) {
        Map<String, KnowledgeMetadataEntity> map = new HashMap<>();
        for (KnowledgeMetadataEntity e : metadataDao.findByChunkIdIn(chunkIds)) {
            map.put(e.getChunkId(), e);
        }
        return map;
    }

    /** 元数据行是否满足过滤；未打标（无行）默认放行。 */
    private boolean allowed(KnowledgeMetadataEntity meta, RetrievalFilter filter) {
        if (meta == null) {
            return true;
        }
        return filter.matches(meta.getDomainTag(), meta.getSubDomainTag(), meta.getRequiredIdentity());
    }

    /**
     * 本地关键词降级检索（Milvus 不可用时）：
     * 查询切分为中文 bigram + 空白分词，对 kb_child_chunk 做 LIKE 召回，
     * 按命中词数在内存打分，取 subTopk 个子块映射到父块累积。
     * <p>
     * <b>诚实声明</b>：这是无向量/无 BM25 索引时的开发降级，相关性弱于 Milvus hybridSearch，
     * 仅保证"无外部依赖可用 + 能命中字面重合知识"，不声称等价。
     */
    private void localKeywordSearch(Accumulator acc, String query) {
        localKeywordSearch(acc, query, RetrievalFilter.none());
    }

    private void localKeywordSearch(Accumulator acc, String query, RetrievalFilter filter) {
        List<String> terms = extractTerms(query);
        if (terms.isEmpty()) {
            return;
        }
        int perTermLimit = Math.max(20, props.retrieve().subTopk() * 2);
        Map<KbChildChunk, Integer> chunkTerms = new HashMap<>();
        for (String term : terms) {
            List<KbChildChunk> rows;
            try {
                rows = childRepo.findByContentContainingIgnoreCaseAndActiveTrue(
                        term, PageRequest.of(0, perTermLimit));
            } catch (Exception e) {
                log.warn("本地关键词检索失败 term={}: {}", term, e.getMessage());
                continue;
            }
            for (KbChildChunk c : rows) {
                chunkTerms.merge(c, 1, Integer::sum);
            }
        }
        // E5/E6：关系库权威过滤，filter 为空也执行（与 Milvus 路径双门控对齐）：
        // knowledge_metadata 行存在且 isActive=false（冲突下线）→ 剔除；再 AND 叠加 ACL 表达式。
        // 子块 active 已由 findByContentContainingIgnoreCaseAndActiveTrue 查询限定。
        if (metadataDao != null && !chunkTerms.isEmpty()) {
            try {
                Map<String, KnowledgeMetadataEntity> meta = loadMetadata(
                        chunkTerms.keySet().stream().map(KbChildChunk::getId).toList());
                chunkTerms.keySet().removeIf(c -> {
                    KnowledgeMetadataEntity m = meta.get(c.getId());
                    if (m != null && Boolean.FALSE.equals(m.getIsActive())) {
                        return true;
                    }
                    return !allowed(m, filter);
                });
            } catch (Exception e) {
                log.warn("本地检索软删/权限过滤失败（保守放行）: {}", e.getMessage());
            }
        }
        chunkTerms.entrySet().stream()
                .sorted(Comparator.<Map.Entry<KbChildChunk, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(props.retrieve().subTopk())
                .forEach(e -> {
                    KbChildChunk c = e.getKey();
                    double score = (double) e.getValue() / terms.size(); // 命中词占比，0~1
                    // §2.5：本地降级路径同样记录代表子块，保证引用六字段两条路径一致
                    acc.put(c.getParentId(), c.getDocId(), c.getId(), c.getChildIndex(), score);
                });
    }

    /** 中文 bigram + 拉丁词切分（去重、限量）。 */
    private List<String> extractTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String trimmed = query.trim();
        if (trimmed.length() == 1) {
            terms.add(trimmed);
            return new ArrayList<>(terms);
        }
        // 拉丁/数字词
        for (String w : trimmed.split("[^A-Za-z0-9_]+")) {
            if (w.length() >= 2) {
                terms.add(w.toLowerCase());
            }
        }
        // 中文（及其他非空白字符）bigram
        char[] chars = trimmed.toCharArray();
        for (int i = 0; i + 1 < chars.length && terms.size() < FALLBACK_MAX_TERMS; i++) {
            if (isCjk(chars[i]) && isCjk(chars[i + 1])) {
                terms.add(trimmed.substring(i, i + 2));
            }
        }
        return new ArrayList<>(terms);
    }

    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /**
     * E2 rerank：可用时对候选父块按 rerank 分数重排 parentOrder。
     * 候选父块内容缺失时跳过；任何异常不阻断，保持原顺序。
     * E3：实际调用 provider 时写 purpose=RERANK 的 model_call_log（成功/失败各一行）；
     * rerank 未启用/不可用/灰度未命中时本方法直接返回——没调就不写日志（诚实打点）。
     * 灰度门控（需求10/15）：wikiagent.gray.features.rerank 按当前请求身份分桶放量。
     */
    private void maybeRerank(Accumulator acc, String query) {
        if (rerankProvider == null || query == null || query.isBlank() || acc.parentOrder.size() < 2) {
            return;
        }
        if (!rerankGrayAllowed()) {
            return;
        }
        String provider = "dashscope";
        String model = rerankProvider instanceof com.wikiagent.infrastructure.llm.DashScopeRerankProvider d
                ? d.model() : rerankProvider.name();
        long started = System.currentTimeMillis();
        try {
            List<String> parentIds = new ArrayList<>(acc.parentOrder);
            Map<String, KbParentChunk> parents = new HashMap<>();
            for (KbParentChunk p : parentRepo.findAllById(parentIds)) {
                parents.put(p.getId(), p);
            }
            List<String> docs = new ArrayList<>(parentIds.size());
            List<String> validIds = new ArrayList<>(parentIds.size());
            for (String pid : parentIds) {
                KbParentChunk p = parents.get(pid);
                if (p == null) continue;
                docs.add(p.getContent());
                validIds.add(pid);
            }
            if (docs.size() < 2) {
                return;
            }
            RerankResult rr = rerankProvider.rerank(new RerankRequest(query, docs, docs.size()));
            if (rr == null || rr.scores() == null || rr.scores().size() != docs.size()) {
                return;
            }
            recordRerank(provider, model, System.currentTimeMillis() - started, true);
            List<String> reordered = new ArrayList<>(validIds);
            List<Double> scores = rr.scores();
            // 按分数降序稳定重排
            List<Integer> idx = new ArrayList<>(validIds.size());
            for (int i = 0; i < validIds.size(); i++) idx.add(i);
            idx.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
            reordered.clear();
            for (int i : idx) reordered.add(validIds.get(i));
            acc.parentOrder.clear();
            acc.parentOrder.addAll(reordered);
            // bestScore 同步为 rerank 分数（保留两位小数精度语义不重要，取原值）
            for (int i = 0; i < validIds.size(); i++) {
                acc.bestScore.put(validIds.get(i), scores.get(i));
            }
        } catch (Exception e) {
            recordRerank(provider, model, System.currentTimeMillis() - started, false);
            log.warn("rerank 失败，保持原序: {}", e.getMessage());
        }
    }

    /**
     * rerank 灰度判定：灰度服务缺失（旧构造/单测）不门控；
     * 灰度键为当前请求身份（RetrievalSecurityContext），无身份归入 anonymous 桶。
     */
    private boolean rerankGrayAllowed() {
        if (grayRelease == null) {
            return true;
        }
        return grayRelease.isEnabled(GRAY_FEATURE_RERANK, RetrievalSecurityContext.currentIdentity());
    }

    /** E3：RERANK 打点；callRecorder 缺失（旧装配/单测）时静默跳过。 */
    private void recordRerank(String provider, String model, long latencyMs, boolean success) {
        if (callRecorder != null) {
            callRecorder.record(ModelCallLogPurpose.RERANK, provider, model,
                    null, null, latencyMs, success, null, null, null);
        }
    }

    /** 累积器 → 父文档上下文：H2 回查父块全文与来源文件名，字符预算内按命中顺序编号组装。 */
    public RetrievalResult assemble(Accumulator acc) {
        return assemble(acc, null);
    }

    /**
     * E2：带 rerank 的组装。rerankProvider 可用时按 query 对候选父块重排，
     * 重排失败/不可用不阻断，按原序返回。
     */
    public RetrievalResult assemble(Accumulator acc, String rerankQuery) {
        if (acc.parentOrder.isEmpty()) {
            return new RetrievalResult(List.of(), "");
        }
        maybeRerank(acc, rerankQuery);

        // H2 回查父块全文与来源文件名（事实源）
        Map<String, KbParentChunk> parents = new HashMap<>();
        for (KbParentChunk p : parentRepo.findAllById(acc.parentOrder)) {
            parents.put(p.getId(), p);
        }
        Set<String> docIds = new LinkedHashSet<>(acc.parentDoc.values());
        Map<String, String> docNames = new HashMap<>();
        for (KbDocument d : docRepo.findAllById(docIds)) {
            docNames.put(d.getId(), d.getFilename());
        }

        // §2.5：批量回查每个父块的代表子块，取 versionNo/pageNo/snippet
        Map<String, KbChildChunk> repChildren = new HashMap<>();
        if (!acc.repChild.isEmpty()) {
            for (KbChildChunk c : childRepo.findByIdIn(
                    acc.repChild.values().stream().map(Accumulator.RepChild::childId).toList())) {
                repChildren.put(c.getId(), c);
            }
        }

        // Task 7 ConflictGuard：rerank 之后、字符预算组装之前，对代表子块做冲突裁决。
        // 移除粒度=chunk（输家 chunk 从该父块的代表子块候选中剔除；当前每父块仅一个代表子块，
        // 剔除后父块无存活代表子块 → 整体剔除）；conflictNote 以独立段落拼进 ctx 末尾。
        String conflictNote = maybeConflictGuard(acc, rerankQuery, repChildren);
        // §2.5：artifactId 由 knowledge_metadata.artifact_id（V11）回填；未打标公共知识为 null
        Map<String, String> artifactIds = new HashMap<>();
        if (metadataDao != null && !repChildren.isEmpty()) {
            for (KnowledgeMetadataEntity m : metadataDao.findByChunkIdIn(new ArrayList<>(repChildren.keySet()))) {
                if (m.getArtifactId() != null) {
                    artifactIds.put(m.getChunkId(), String.valueOf(m.getArtifactId()));
                }
            }
        }

        int budget = props.retrieve().parentCharBudget();
        StringBuilder ctx = new StringBuilder();
        List<Source> sources = new ArrayList<>();
        int used = 0;
        int idx = 1;
        for (String parentId : acc.parentOrder) {
            KbParentChunk p = parents.get(parentId);
            if (p == null) {
                continue; // Milvus 与 H2 不一致（如刚被删除），跳过
            }
            String content = p.getContent();
            int remaining = budget - used;
            if (remaining <= 200) {
                break;
            }
            if (content.length() > remaining) {
                content = content.substring(0, remaining);
            }
            String filename = docNames.getOrDefault(p.getDocId(), "未知文档");
            ctx.append('[').append(idx).append("] 来源: ").append(filename).append('\n')
                    .append(content).append("\n\n");
            used += content.length();
            Accumulator.RepChild rc = acc.repChild.get(parentId);
            KbChildChunk child = rc == null ? null : repChildren.get(rc.childId());
            sources.add(new Source(idx, p.getDocId(),
                    child != null ? child.getVersionNo() : 0,
                    child != null ? child.getPageNo() : null,
                    snippetOf(child == null ? null : child.getContent()),
                    rc == null ? null : artifactIds.get(rc.childId()),
                    acc.bestScore.getOrDefault(parentId, 0.0), filename));
            idx++;
        }
        // Task 7：冲突标注段（仅"保留但存在冲突"时非空）以独立段落拼进 ctx 末尾
        if (conflictNote != null && !conflictNote.isBlank()) {
            ctx.append(conflictNote);
        }
        return new RetrievalResult(sources, ctx.toString());
    }

    /**
     * Task 7 ConflictGuard：对最终候选的代表子块做冲突裁决（守卫缺席/候选不足/查询为空时跳过）。
     * <p>
     * 输家 chunk 从其父块的代表子块候选中剔除；当前累积器每父块仅维护一个代表子块，
     * 剔除后父块无存活代表子块 → 父块整体从 parentOrder 剔除（不进入本次上下文与引用）。
     * 守卫内部 fail-open（开关/灰度/异常均原样透传），此处不做二次兜底。
     *
     * @return conflictNote（nullable，拼进 ctx 末尾的冲突标注段）
     */
    private String maybeConflictGuard(Accumulator acc, String query, Map<String, KbChildChunk> repChildren) {
        if (conflictGuard == null || query == null || query.isBlank() || acc.parentOrder.size() < 2) {
            return null;
        }
        // 代表子块候选（保持 parentOrder 命中顺序）
        List<String> candParentIds = new ArrayList<>();
        List<ConflictCandidateDetector.RepChunkView> candidates = new ArrayList<>();
        for (String parentId : acc.parentOrder) {
            Accumulator.RepChild rc = acc.repChild.get(parentId);
            if (rc == null) {
                continue; // 无代表子块（测试辅助命中），不参与冲突裁决
            }
            KbChildChunk child = repChildren.get(rc.childId());
            if (child == null) {
                continue;
            }
            candParentIds.add(parentId);
            candidates.add(new ConflictCandidateDetector.RepChunkView(
                    child.getId(), child.getDocId(), child.getContent(), rc.score()));
        }
        if (candidates.size() < 2) {
            return null;
        }
        RetrievalConflictGuard.GuardResult result = conflictGuard.apply(query, candidates);
        if (result == null) {
            return null;
        }
        Set<String> keptIds = new HashSet<>();
        for (ConflictCandidateDetector.RepChunkView c : result.kept()) {
            keptIds.add(c.childId());
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (!keptIds.contains(candidates.get(i).childId())) {
                String parentId = candParentIds.get(i);
                acc.repChild.remove(parentId);
                acc.parentOrder.remove(parentId);
                acc.bestScore.remove(parentId);
                acc.parentDoc.remove(parentId);
            }
        }
        return result.conflictNote();
    }

    /** §2.5：引用片段 = 子块原文去空白后前 200 字符；子块缺失/空时为 null。 */
    private static String snippetOf(String childContent) {
        if (childContent == null) {
            return null;
        }
        String s = childContent.strip();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
