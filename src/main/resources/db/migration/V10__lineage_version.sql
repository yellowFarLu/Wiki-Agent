-- V10__lineage_version.sql: 子项目C 文档血缘与版本数据模型（规格 §C1）
-- 5 张表：doc_artifact / provenance_edge / extracted_field / field_version / doc_version
-- 兼容 H2 (MODE=MySQL) 与 MySQL 8：JSON 列用 TEXT；外键为软关联（不建物理 FK，便于跨介质/版本）

-- ============ 文档产物（大文本不入库，存 contentRef 路径 + sha256）============
CREATE TABLE IF NOT EXISTS doc_artifact (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    doc_id        VARCHAR(64)  NOT NULL,
    version_no    INT          NOT NULL DEFAULT 1,
    artifact_type VARCHAR(32)  NOT NULL,         -- PARSED_TEXT / OCR_PAGE / LAYOUT / STITCHED_TABLE / EXTRACT_RESULT / CLEANED_TEXT
    content_ref   VARCHAR(512) NOT NULL,         -- data/uploads/{docId}/artifacts/{name}
    sha256        VARCHAR(64)  NOT NULL,
    size_bytes    BIGINT       NOT NULL DEFAULT 0,
    page_no       INT,                           -- 页级产物（OCR/LAYOUT）的来源页
    created_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uk_artifact_doc_type_page UNIQUE (doc_id, version_no, artifact_type, page_no)
);
CREATE INDEX idx_artifact_doc ON doc_artifact (doc_id, version_no);

-- ============ 血缘边（产物/字段/版本间的有向依赖）============
CREATE TABLE IF NOT EXISTS provenance_edge (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    doc_id          VARCHAR(64)  NOT NULL,
    version_no      INT          NOT NULL DEFAULT 1,
    from_ref        VARCHAR(128) NOT NULL,        -- artifactId / fieldKey / versionNo
    from_type       VARCHAR(32)  NOT NULL,        -- ARTIFACT / FIELD / DOC_VERSION
    to_ref          VARCHAR(128) NOT NULL,
    to_type         VARCHAR(32)  NOT NULL,
    edge_type       VARCHAR(24)  NOT NULL,        -- DERIVED / EXTRACTED / STITCHED / RULED / EDITED / CITED / SUPERSEDES
    note            VARCHAR(512),
    created_at      TIMESTAMP    NOT NULL
);
CREATE INDEX idx_edge_doc ON provenance_edge (doc_id, version_no);
CREATE INDEX idx_edge_from ON provenance_edge (doc_id, from_ref);
CREATE INDEX idx_edge_to ON provenance_edge (doc_id, to_ref);

-- ============ 抽取字段（当前快照，每文档每字段唯一）============
CREATE TABLE IF NOT EXISTS extracted_field (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    doc_id          VARCHAR(64)  NOT NULL,
    field_key       VARCHAR(128) NOT NULL,
    field_label     VARCHAR(255),
    value_text      TEXT,
    value_type      VARCHAR(16),                 -- STRING / NUMBER / DATE / BOOLEAN
    confidence      DOUBLE       NOT NULL DEFAULT 1.0,
    source          VARCHAR(16)  NOT NULL,       -- MODEL / RULE / HUMAN
    schema_key      VARCHAR(128),
    schema_version  VARCHAR(32),
    valid           TINYINT(1)   NOT NULL DEFAULT 1,
    review_required TINYINT(1)   NOT NULL DEFAULT 0,
    version_no      INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_field_doc_key UNIQUE (doc_id, field_key)
);
CREATE INDEX idx_field_doc ON extracted_field (doc_id, version_no);

-- ============ 字段版本（不可变历史，每次值/置信/来源变更写一行）============
CREATE TABLE IF NOT EXISTS field_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    doc_id          VARCHAR(64)  NOT NULL,
    field_key       VARCHAR(128) NOT NULL,
    version_no      INT          NOT NULL,
    value_text      TEXT,
    confidence      DOUBLE       NOT NULL DEFAULT 1.0,
    source          VARCHAR(16)  NOT NULL,
    edited_by       VARCHAR(64),
    change_reason   VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_field_ver UNIQUE (doc_id, field_key, version_no)
);
CREATE INDEX idx_field_ver_doc ON field_version (doc_id, field_key);

-- ============ 文档版本（重解析产生新版本，旧 chunk is_active=false）============
CREATE TABLE IF NOT EXISTS doc_version (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    doc_id            VARCHAR(64)  NOT NULL,
    version_no        INT          NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',  -- DRAFT / PUBLISHED / SUPERSEDED
    parent_version_no INT,                       -- SUPERSEDES 链：上一版本号
    change_summary    VARCHAR(512),
    artifact_sha256   VARCHAR(64),               -- 源文件指纹，用于判定是否需重解析
    created_by        VARCHAR(64),
    created_at        TIMESTAMP    NOT NULL,
    CONSTRAINT uk_doc_ver UNIQUE (doc_id, version_no)
);
CREATE INDEX idx_doc_ver_doc ON doc_version (doc_id, status);
