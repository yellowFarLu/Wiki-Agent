package com.wikiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "wikiagent")
public record WikiAgentProperties(Milvus milvus, Retrieve retrieve, Ingest ingest, Agent agent,
                                  Dedup dedup, ConflictGuard conflictGuard) {

    /**
     * 绑定构造：存在多个构造器时必须显式标注，否则 Spring 回退找无参构造而实例化失败。
     * <p>
     * Task 10 E2E 修复（wiring 遗漏）：{@code conflictGuard} 改用 <b>虚线式扁平名</b>
     * {@code wikiagent.conflict-guard.*} 绑定。原因：{@code wikiagent.conflict} 命名空间
     * 已被每日扫描配置（threshold/scan-cron）占用，{@code wikiagent.conflict.guard.enabled}
     * 的 {@code conflict.guard} 是两级路径，无法绑定到单级组件 {@code conflictGuard}
     * （E2E 实测：文档化开关设 true 时 judge 条件 Bean 装配但守卫 record 为 null →
     * 恒透传）。双点泾渭分明：{@code wikiagent.conflict.*}=离线扫描，
     * {@code wikiagent.conflict-guard.*}=检索侧守卫。
     */
    @ConstructorBinding
    public WikiAgentProperties {
    }

    /** 兼容旧装配（4 参构造）：dedup/conflictGuard 缺省为 null，消费方按"关闭"处理。 */
    public WikiAgentProperties(Milvus milvus, Retrieve retrieve, Ingest ingest, Agent agent) {
        this(milvus, retrieve, ingest, agent, null);
    }

    /** 兼容 Task 4 装配（5 参构造）：conflictGuard 缺省为 null，消费方按"关闭"处理。 */
    public WikiAgentProperties(Milvus milvus, Retrieve retrieve, Ingest ingest, Agent agent,
                               Dedup dedup) {
        this(milvus, retrieve, ingest, agent, dedup, null);
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
     * 默认开启；{@code dedup} 整体缺省（null，仅手工构造的测试装配）时消费方按关闭处理。
     */
    public record Dedup(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.9") double nearJaccard) {
    }

    /**
     * 检索侧冲突守卫配置（粗筛余弦阈值 + qwen-flash 精判 + 生效时间裁决）。
     * 默认开启；{@code conflictGuard} 整体缺省（null，仅手工构造的测试装配）时消费方按关闭处理。
     */
    public record ConflictGuard(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.85") double coarseCosine) {
    }
}
