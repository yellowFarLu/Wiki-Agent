package com.wikiagent.application.agent;

import com.wikiagent.config.ToolPermissionProperties;
import com.wikiagent.domain.tool.ToolCaller;
import com.wikiagent.domain.tool.ToolPermission;
import com.wikiagent.infrastructure.tool.ToolPermissionEntity;
import com.wikiagent.infrastructure.tool.ToolPermissionJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子项目 F1：工具权限注册表（toolName → requiredRole / requiredScope / requiresApproval）
 * + 每 Agent 工具白名单（AgentToolAllowlist 语义）。
 * <p>
 * 权限来源优先级（高覆盖低）：
 * <ol>
 *   <li>配置 {@code wikiagent.tool-permissions.permissions}（部署期固化）</li>
 *   <li>DB 表 tool_permission（V15，运营可改）</li>
 *   <li>内置默认（update_user_profile 为写工具，需人工批准）</li>
 * </ol>
 * Agent 白名单来源：{@code wikiagent.tool-permissions.agent-allowlists}；
 * 未配置的 Agent 使用 {@link #DEFAULT_ALLOWLIST}。
 */
@Service
public class ToolPermissionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionRegistry.class);

    /** 全部已知工具名（与 ToolRegistryImpl/DefaultToolRegistry 保持一致）。 */
    public static final List<String> ALL_TOOLS = List.of(
            "search_knowledge_base", "search_history", "update_user_profile",
            "read_handover", "list_abandoned_paths",
            "query_order", "query_trajectory", "generate_customs_info");

    /** 未配置 Agent 时的默认白名单：全部已知工具。 */
    public static final List<String> DEFAULT_ALLOWLIST = ALL_TOOLS;

    /** 内置默认权限：写工具 update_user_profile 需人工批准；业务查询需对应 scope（免批准）。 */
    private static final Map<String, ToolPermission> BUILTIN = Map.of(
            "update_user_profile", new ToolPermission("update_user_profile", null, null, true),
            "query_order", new ToolPermission("query_order", null, "order:read", false),
            "query_trajectory", new ToolPermission("query_trajectory", null, "order:read", false),
            "generate_customs_info", new ToolPermission("generate_customs_info", null, "customs:write", false));

    private final ToolPermissionProperties props;
    private final ToolPermissionJpaDao dao;

    public ToolPermissionRegistry(ToolPermissionProperties props,
                                  ObjectProvider<ToolPermissionJpaDao> daoProvider) {
        this.props = props;
        this.dao = daoProvider.getIfAvailable();
    }

    /** 解析工具权限：配置 > DB > 内置默认 > 无限制。 */
    public ToolPermission permissionOf(String toolName) {
        ToolPermissionProperties.PermissionSpec spec = props.getPermissions().get(toolName);
        if (spec != null) {
            return new ToolPermission(toolName, blank2null(spec.getRequiredRole()),
                    blank2null(spec.getRequiredScope()), spec.isRequiresApproval());
        }
        if (dao != null) {
            try {
                var row = dao.findByToolName(toolName);
                if (row.isPresent()) {
                    ToolPermissionEntity e = row.get();
                    return new ToolPermission(toolName, blank2null(e.getRequiredRole()),
                            blank2null(e.getRequiredScope()), e.isRequiresApproval());
                }
            } catch (Exception e) {
                // DB 不可达时降级内置默认，不阻断工具链路
                log.warn("tool_permission 查询失败，降级内置默认 tool={}: {}", toolName, e.getMessage());
            }
        }
        return BUILTIN.getOrDefault(toolName, ToolPermission.unrestricted(toolName));
    }

    /** 某 Agent 的工具白名单；未配置返回默认白名单。 */
    public List<String> allowlistOf(String agentName) {
        List<String> list = agentName == null ? null : props.getAgentAllowlists().get(agentName);
        return (list == null || list.isEmpty()) ? DEFAULT_ALLOWLIST : List.copyOf(list);
    }

    /**
     * 调用前权限判定：白名单 → 角色 → scope，全部通过才放行。
     *
     * @return null 表示放行；非 null 为拒绝原因（供 TOOL_DENIED 审计 detail）
     */
    public String denyReason(String toolName, ToolCaller caller, String agentName) {
        if (!allowlistOf(agentName).contains(toolName)) {
            return "NOT_IN_ALLOWLIST: agent=" + agentName + " 不允许调用 " + toolName;
        }
        ToolPermission perm = permissionOf(toolName);
        if (perm.roleRestricted() && (caller == null || !caller.hasRole(perm.requiredRole()))) {
            return "ROLE_INSUFFICIENT: 需要角色 " + perm.requiredRole()
                    + "，实际 " + (caller == null ? null : caller.role());
        }
        if (perm.scopeRestricted() && (caller == null || !caller.hasScope(perm.requiredScope()))) {
            return "SCOPE_MISMATCH: 需要 scope " + perm.requiredScope();
        }
        return null;
    }

    public boolean allow(String toolName, ToolCaller caller, String agentName) {
        return denyReason(toolName, caller, agentName) == null;
    }

    /** 该工具是否被标记为高危（执行前需 TOOL_APPROVAL 人工批准）。 */
    public boolean requiresApproval(String toolName) {
        return permissionOf(toolName).requiresApproval();
    }

    private static String blank2null(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
