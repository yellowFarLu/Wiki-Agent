-- V15__agent_governance.sql: 子项目F Agent 治理增强（规格 §F1 工具权限）
-- tool_permission：工具→角色/scope/批准语义映射，兼容 H2 (MODE=MySQL) 与 MySQL 8

CREATE TABLE IF NOT EXISTS tool_permission (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tool_name         VARCHAR(64)  NOT NULL,
    required_role     VARCHAR(64),                 -- 空 = 不校验角色
    required_scope    VARCHAR(64),                 -- 空 = 不校验 scope
    requires_approval TINYINT(1)  NOT NULL DEFAULT 0,  -- 1 = 高危工具，执行前需 TOOL_APPROVAL
    updated_at        TIMESTAMP    NOT NULL,
    CONSTRAINT uk_tool_permission_name UNIQUE (tool_name)
);
