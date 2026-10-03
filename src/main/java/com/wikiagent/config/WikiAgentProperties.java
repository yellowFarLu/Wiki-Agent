package com.wikiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "wikiagent")
public record WikiAgentProperties(Milvus milvus, Retrieve retrieve, Ingest ingest, Agent agent, Dedup dedup) {

    /** 绑定构造：存在多个构造器时必须显式标注，否则 Spring 回退找无参构造而实例化失败。 */
    @ConstructorBinding
    public WikiAgentProperties {
    }

    /** 兼容旧装配（4 参构造）：dedup 缺省为 null，消费方按"关闭"处理。 */
    public WikiAgentProperties(Milvus milvus, Retrieve retrieve, Ingest ingest, Agent agent) {
        this(milvus, retrieve, ingest, agent, null);
    }

    /** Agentic RAG 编排配置。 */
    public record Agent(
            boolean enabled,
            int maxRounds,
            int maxQueriesPerRound) {
    }

    public record Milvus(
            String uri,
            String username,
            String password,
            String database,
            String collection,
            int dimension,
            double bm25K1,
            double bm25B) {
    }

    public record Retrieve(
            int subTopk,
            int finalTopk,
            int rrfK,
            int parentCharBudget) {
    }

    public record Ingest(
            int parentChars,
            int parentOverlap,
            int childChars,
            int childOverlap,
            int embeddingBatch) {
    }

    /**
     * 入库去重配置（L0 精确 hash + L1 MinHash-LSH 近重复守卫）。
     * 默认关闭；{@code dedup} 整体缺省（null）时消费方按关闭处理。
     */
    public record Dedup(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("0.9") double nearJaccard) {
    }
}
