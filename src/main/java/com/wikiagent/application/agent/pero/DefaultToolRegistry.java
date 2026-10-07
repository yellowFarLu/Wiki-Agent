package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.PlanStep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v6 §20 ToolRegistry 端口的默认实现（自洽兜底）。
 * <p>
 * §20.6 配置 {@code wikiagent.pero.react.tool-whitelist-by-step-type} 定义按节点类型的工具白名单：
 * <ul>
 *   <li>search_kb: [search_knowledge_base]</li>
 *   <li>search_history: [search_history]</li>
 *   <li>update_profile: [update_user_profile]</li>
 *   <li>tool: 全部工具</li>
 *   <li>generate: []（无需工具）</li>
 * </ul>
 * v3-v5 实施时由 {@code ToolRegistry + ToolPolicyInterceptor} 替换（基于 Spring AI @Tool）。
 */
@Component
public class DefaultToolRegistry implements ToolRegistry {

    private static final List<String> ALL_TOOLS = List.of(
            "search_knowledge_base", "search_history", "update_user_profile",
            "read_handover", "list_abandoned_paths",
            "query_order", "query_trajectory", "generate_customs_info");

    @Override
    public List<String> allowedTools(PlanStep step) {
        if (step == null || step.stepType() == null) {
            return ALL_TOOLS;
        }
        return switch (step.stepType()) {
            case "search_kb" -> List.of("search_knowledge_base");
            case "search_history" -> List.of("search_history");
            case "update_profile" -> List.of("update_user_profile");
            case "business_query" -> List.of("query_order", "query_trajectory");
            case "customs_generate" -> List.of("generate_customs_info");
            case "generate" -> List.of();
            default -> ALL_TOOLS;
        };
    }
}
