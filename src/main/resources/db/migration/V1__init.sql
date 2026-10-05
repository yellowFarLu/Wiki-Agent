-- V1__init.sql: 基础表（kb_document + kb_parent_chunk + kb_child_chunk + user_profile）
-- 兼容 H2 MySQL 模式 + MySQL 生产环境
-- 使用 CREATE TABLE IF NOT EXISTS 避免与 JPA ddl-auto: update 冲突

-- 知识库文档元数据
CREATE TABLE IF NOT EXISTS kb_document (
    id            VARCHAR(64)  NOT NULL PRIMARY KEY,
    filename      VARCHAR(255) NOT NULL,
    doc_type      VARCHAR(16)  NOT NULL,
    size_bytes    BIGINT       NOT NULL DEFAULT 0,
    status        VARCHAR(24)  NOT NULL DEFAULT 'PARSING',
    parent_count  INT          NOT NULL DEFAULT 0,
    child_count   INT          NOT NULL DEFAULT 0,
    error         VARCHAR(2000),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 父文档块（大粒度，用于上下文回填）
CREATE TABLE IF NOT EXISTS kb_parent_chunk (
    id            VARCHAR(64)   NOT NULL PRIMARY KEY,
    doc_id        VARCHAR(64)   NOT NULL,
    parent_index  INT           NOT NULL,
    content       MEDIUMTEXT    NOT NULL
);

-- 子文档块（小粒度，用于向量检索）
CREATE TABLE IF NOT EXISTS kb_child_chunk (
    id            VARCHAR(64)   NOT NULL PRIMARY KEY,
    doc_id        VARCHAR(64)   NOT NULL,
    parent_id     VARCHAR(64)   NOT NULL,
    child_index   INT           NOT NULL,
    content       VARCHAR(4000) NOT NULL
);

-- v1-v2 §5 用户档案表（v3 扩 3 字段：business_identity / overrides / assigned_domains）
CREATE TABLE IF NOT EXISTS user_profile (
    user_id             VARCHAR(64)   NOT NULL PRIMARY KEY,
    display_name        VARCHAR(128),
    preferred_language  VARCHAR(16)  DEFAULT 'zh-CN',
    business_identity   VARCHAR(32)  DEFAULT 'business',
    overrides           TEXT,                    -- JSON: 手动覆盖的领域权限
    assigned_domains    TEXT,                    -- JSON: 管理员分配的领域列表
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
