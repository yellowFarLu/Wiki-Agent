package com.wikiagent.service.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.entity.KbChildChunk;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Milvus V2 SDK 封装：
 * - collection: dense(FloatVector) + sparse(BM25 Function 自动生成) 双向量 + 标量元数据
 * - hybridSearch: 一次请求双路召回，服务端 RRFRanker 融合
 * 连接失败不阻断应用启动，由 /api/health 反馈状态。
 */
@Service
public class MilvusStoreService {

    private static final Logger log = LoggerFactory.getLogger(MilvusStoreService.class);
    private static final Gson gson = new Gson();

    private final WikiAgentProperties props;
    private final AtomicReference<MilvusClientV2> clientRef = new AtomicReference<>();
    private volatile String lastError = "尚未连接";

    /**
     * 修复4/E6：服务端表达式优先只取 is_active=true 向量行，避免扫描已下线向量。
     * 注意：这只是性能侧的第一道筛——Milvus 行不做物理删除，冲突下线/重解析只翻关系库
     * 软删标志，权威门控在 RetrievalService：无论本开关取值，检索结果都以关系库
     * kb_child_chunk.active 与 knowledge_metadata.is_active 双门控为准。
     * 旧版手工创建、未含 is_active 列的存量集合需重建集合或手动 ALTER ADD COLUMN；
     * 升级过渡期可置 false 关闭服务端过滤（不影响关系库双门控）。
     */
    @Value("${wikiagent.milvus.filter-active:true}")
    private boolean filterActive;

    public MilvusStoreService(WikiAgentProperties props) {
        this.props = props;
    }

    public synchronized MilvusClientV2 client() {
        MilvusClientV2 c = clientRef.get();
        if (c != null) {
            return c;
        }
        try {
            WikiAgentProperties.Milvus mc = props.milvus();
            ConnectConfig cfg = ConnectConfig.builder()
                    .uri(mc.uri())
                    .username(mc.username())
                    .password(mc.password())
                    .dbName(mc.database() == null ? "default" : mc.database())
                    .build();
            c = new MilvusClientV2(cfg);
            c.getServerVersion(); // 触发一次真实 RPC，验证连通
            clientRef.set(c);
            lastError = null;
            log.info("Milvus 连接成功: {}", mc.uri());
            return c;
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Milvus 连接失败: {}", lastError);
            throw new IllegalStateException("Milvus 不可用: " + lastError, e);
        }
    }

    public String serverVersion() {
        return client().getServerVersion();
    }

    public String lastError() {
        return lastError;
    }

    /**
     * 确保 collection 存在（首次自动创建：双向量字段 + BM25 Function + 索引 + load）。
     */
    public synchronized void ensureCollection() {
        MilvusClientV2 c = client();
        String coll = props.milvus().collection();
        Boolean exists = c.hasCollection(HasCollectionReq.builder().collectionName(coll).build());
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        int dim = props.milvus().dimension();

        CreateCollectionReq.CollectionSchema schema = c.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName("id").dataType(DataType.VarChar).maxLength(64).isPrimaryKey(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("text").dataType(DataType.VarChar).maxLength(65535)
                .enableAnalyzer(true).analyzerParams(Map.of("type", "chinese")).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("sparse").dataType(DataType.SparseFloatVector).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("dense").dataType(DataType.FloatVector).dimension(dim).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("doc_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("parent_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("child_index").dataType(DataType.Int32).build());
        // 修复4：版本化检索——重解析后旧版本行 is_active=false 被过滤；page_no=0 表示无分页
        schema.addField(AddFieldReq.builder()
                .fieldName("version_no").dataType(DataType.Int32).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("is_active").dataType(DataType.Bool).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("page_no").dataType(DataType.Int32).build());
        // E5：权限标量字段（用于 filter 下推）
        schema.addField(AddFieldReq.builder()
                .fieldName("domain_tag").dataType(DataType.VarChar).maxLength(64).isNullable(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("sub_domain_tag").dataType(DataType.VarChar).maxLength(64).isNullable(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("required_identity").dataType(DataType.VarChar).maxLength(64).isNullable(true).build());

        schema.addFunction(CreateCollectionReq.Function.builder()
                .functionType(FunctionType.BM25)
                .name("text_bm25_emb")
                .inputFieldNames(List.of("text"))
                .outputFieldNames(List.of("sparse"))
                .build());

        List<IndexParam> indexes = List.of(
                IndexParam.builder()
                        .fieldName("sparse")
                        .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                        .metricType(IndexParam.MetricType.BM25)
                        .extraParams(Map.of(
                                "bm25_k1", props.milvus().bm25K1(),
                                "bm25_b", props.milvus().bm25B(),
                                "inverted_index_algo", "DAAT_MAXSCORE"))
                        .build(),
                IndexParam.builder()
                        .fieldName("dense")
                        .indexType(IndexParam.IndexType.HNSW)
                        .metricType(IndexParam.MetricType.COSINE)
                        .extraParams(Map.of("M", 16, "efConstruction", 200))
                        .build());

        c.createCollection(CreateCollectionReq.builder()
                .collectionName(coll)
                .collectionSchema(schema)
                .indexParams(indexes)
                .build());
        c.loadCollection(LoadCollectionReq.builder().collectionName(coll).build());
        log.info("Milvus collection 已创建: {} (dim={})", coll, dim);
    }

    /**
     * 批量写入子块。sparse 字段由服务端 BM25 Function 自动生成，客户端不传。
     */
    public void insertChildren(List<KbChildChunk> children, List<float[]> vectors) {
        if (children.size() != vectors.size()) {
            throw new IllegalArgumentException("children/vectors size mismatch");
        }
        ensureCollection();
        List<JsonObject> rows = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            KbChildChunk ch = children.get(i);
            JsonObject row = new JsonObject();
            row.addProperty("id", ch.getId());
            row.addProperty("text", ch.getContent());
            row.add("dense", gson.toJsonTree(vectors.get(i)));
            row.addProperty("doc_id", ch.getDocId());
            row.addProperty("parent_id", ch.getParentId());
            row.addProperty("child_index", ch.getChildIndex());
            row.addProperty("version_no", ch.getVersionNo());
            row.addProperty("is_active", ch.isActive());
            row.addProperty("page_no", ch.getPageNo() == null ? 0 : ch.getPageNo());
            rows.add(row);
        }
        client().insert(InsertReq.builder()
                .collectionName(props.milvus().collection())
                .data(rows)
                .build());
    }

    public record Hit(String childId, double score, String docId, String parentId, int childIndex,
                      int versionNo, int pageNo) {
    }

    /**
     * 混合检索：dense(查询向量) + sparse(原始查询文本，服务端 BM25 向量化) → RRFRanker 融合。
     */
    public List<Hit> hybridSearch(float[] queryEmbedding, String rewrittenQuery,
                                  int subTopk, int finalTopk, int rrfK) {
        return hybridSearch(queryEmbedding, rewrittenQuery, subTopk, finalTopk, rrfK, null);
    }

    /**
     * E5 带权限表达式下推的混合检索：extraExpr（如 domain_tag/required_identity 标量过滤）
     * 与 is_active 条件 AND 合并后下推。
     * <p>
     * 注意：下推要求 collection 建表时含相应标量字段；存量集合缺列时表达式会报错，
     * 由调用方捕获并走关系库侧过滤兜底（wikiagent.milvus.filter-metadata 默认 false 不下推）。
     */
    public List<Hit> hybridSearch(float[] queryEmbedding, String rewrittenQuery,
                                  int subTopk, int finalTopk, int rrfK, String extraExpr) {
        ensureCollection();
        List<BaseVector> denseVecs = List.of(new FloatVec(queryEmbedding));
        List<BaseVector> sparseVecs = List.of(new EmbeddedText(rewrittenQuery));

        String activeExpr = filterActive ? "is_active == true" : null;
        if (extraExpr != null && !extraExpr.isBlank()) {
            activeExpr = activeExpr == null ? extraExpr : activeExpr + " and (" + extraExpr + ")";
        }
        AnnSearchReq.AnnSearchReqBuilder denseB = AnnSearchReq.builder()
                .vectorFieldName("dense")
                .vectors(denseVecs)
                .topK(subTopk);
        AnnSearchReq.AnnSearchReqBuilder sparseB = AnnSearchReq.builder()
                .vectorFieldName("sparse")
                .vectors(sparseVecs)
                .topK(subTopk);
        if (activeExpr != null) {
            denseB.expr(activeExpr);
            sparseB.expr(activeExpr);
        }
        AnnSearchReq denseReq = denseB.build();
        AnnSearchReq sparseReq = sparseB.build();

        HybridSearchReq req = HybridSearchReq.builder()
                .collectionName(props.milvus().collection())
                .searchRequests(List.of(denseReq, sparseReq))
                .ranker(new RRFRanker(rrfK))
                .limit(finalTopk)
                .outFields(List.of("doc_id", "parent_id", "child_index"))
                .build();

        SearchResp resp = client().hybridSearch(req);
        List<SearchResp.SearchResult> hits = resp.getSearchResults().get(0);
        List<Hit> result = new ArrayList<>(hits.size());
        for (SearchResp.SearchResult hit : hits) {
            Map<String, Object> entity = hit.getEntity();
            result.add(new Hit(
                    String.valueOf(hit.getId()),
                    hit.getScore() == null ? 0 : hit.getScore(),
                    String.valueOf(entity.getOrDefault("doc_id", "")),
                    String.valueOf(entity.getOrDefault("parent_id", "")),
                    entity.get("child_index") instanceof Number n ? n.intValue() : 0,
                    // 兼容旧 schema：version_no / page_no 在部分存量 collection 中不存在，取不到时按 1 / 0 处理
                    entity.get("version_no") instanceof Number vn ? vn.intValue() : 1,
                    entity.get("page_no") instanceof Number pn ? pn.intValue() : 0));
        }
        return result;
    }

    public void deleteByDocId(String docId) {
        ensureCollection();
        client().delete(DeleteReq.builder()
                .collectionName(props.milvus().collection())
                .filter("doc_id == \"" + docId.replace("\"", "") + "\"")
                .build());
    }
}
