package com.wikiagent.infrastructure.tool;

import com.wikiagent.application.agent.pero.ToolRegistry;
import com.wikiagent.domain.agent.PlanStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v1-v2 §2.6 工具注册表实现。
 * <p>
 * 实现 v6 pero 包的 {@link ToolRegistry} 端口契约，作为 v1-v2 路径的工具注册中心：
 * <ul>
 *   <li>持有 5 个 @Tool 实现（按工具名注册）</li>
 *   <li>提供按名查找 lookup(name)</li>
 *   <li>实现 {@link #allowedTools(PlanStep)}：按 PlanStep.stepType() 返回白名单
 *       （与 {@code DefaultToolRegistry} 逻辑一致，保证 v6 ReActExecutor 注入本 Bean 时行为自洽）</li>
 * </ul>
 * <p>
 * 工具 #1（SearchKnowledgeBaseTool）在 v1-v2 路径必然存在，直接注入；
 * 工具 #2-#5 受 @ConditionalOnBean 约束（对应端口实现缺失时不创建），
 * 故用 {@link ObjectProvider} 容错注入，缺省则不注册。
 * <p>
 * 用 @Primary 解决与 v6 {@code DefaultToolRegistry} 的多 Bean 冲突：
 * {@code wikiagent.pero.enabled=false} 时两者并存，本类优先注入 ReActExecutor。
 */
@Component
@Primary
@ConditionalOnProperty(name = "wikiagent.pero.enabled", havingValue = "false")
public class ToolRegistryImpl implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryImpl.class);

    /** 全部已知工具名（与 DefaultToolRegistry 一致）。 */
    private static final List<String> ALL_TOOLS = List.of(
            SearchKnowledgeBaseTool.NAME, SearchHistoryTool.NAME, UpdateUserProfileTool.NAME,
            ReadHandoverTool.NAME, ListAbandonedPathTool.NAME,
            QueryOrderTool.NAME, QueryTrajectoryTool.NAME, GenerateCustomsInfoTool.NAME);

    private final Map<String, Object> tools = new LinkedHashMap<>();

    public ToolRegistryImpl(SearchKnowledgeBaseTool searchKb,
                            ObjectProvider<SearchHistoryTool> searchHistoryProvider,
                            ObjectProvider<UpdateUserProfileTool> updateProfileProvider,
                            ObjectProvider<ReadHandoverTool> readHandoverProvider,
                            ObjectProvider<ListAbandonedPathTool> listAbandonedProvider,
                            ObjectProvider<QueryOrderTool> queryOrderProvider,
                            ObjectProvider<QueryTrajectoryTool> queryTrajectoryProvider,
                            ObjectProvider<GenerateCustomsInfoTool> generateCustomsProvider) {
        // 工具 #1：v1-v2 路径必然存在
        register(SearchKnowledgeBaseTool.NAME, searchKb);
        // 工具 #2-#5：端口实现缺失时不创建，容错注入
        registerIfPresent(SearchHistoryTool.NAME, searchHistoryProvider);
        registerIfPresent(UpdateUserProfileTool.NAME, updateProfileProvider);
        registerIfPresent(ReadHandoverTool.NAME, readHandoverProvider);
        registerIfPresent(ListAbandonedPathTool.NAME, listAbandonedProvider);
        // 业务工具（订单/轨迹/清关）
        registerIfPresent(QueryOrderTool.NAME, queryOrderProvider);
        registerIfPresent(QueryTrajectoryTool.NAME, queryTrajectoryProvider);
        registerIfPresent(GenerateCustomsInfoTool.NAME, generateCustomsProvider);
        log.info("v1-v2 工具注册表初始化完成，已注册 {} 个工具: {}",
                tools.size(), tools.keySet());
    }

    private void register(String name, Object tool) {
        tools.put(name, tool);
    }

    private void registerIfPresent(String name, ObjectProvider<?> provider) {
        Object bean = provider.getIfAvailable();
        if (bean != null) {
            tools.put(name, bean);
        } else {
            log.info("工具 {} 对应端口未实现，跳过注册", name);
        }
    }

    /** 按名查找工具实例。 */
    public Optional<Object> lookup(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 是否已注册某工具。 */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /** 已注册的全部工具名。 */
    public List<String> registeredNames() {
        return List.copyOf(tools.keySet());
    }

    /**
     * 按 PlanStep.stepType() 返回工具白名单。
     * <p>
     * 与 v6 {@code DefaultToolRegistry#allowedTools} 逻辑保持一致，
     * 保证本 Bean 作为 @Primary 注入 ReActExecutor 时白名单行为自洽。
     */
    @Override
    public List<String> allowedTools(PlanStep step) {
        if (step == null || step.stepType() == null) {
            return ALL_TOOLS;
        }
        return switch (step.stepType()) {
            case "search_kb" -> List.of(SearchKnowledgeBaseTool.NAME);
            case "search_history" -> List.of(SearchHistoryTool.NAME);
            case "update_profile" -> List.of(UpdateUserProfileTool.NAME);
            case "business_query" -> List.of(QueryOrderTool.NAME, QueryTrajectoryTool.NAME);
            case "customs_generate" -> List.of(GenerateCustomsInfoTool.NAME);
            case "generate" -> List.of();
            default -> ALL_TOOLS;
        };
    }
}
