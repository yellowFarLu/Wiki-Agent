-- V19__dedup_conflict_guard.sql: RAG 入库去重 + 冲突守卫 schema
-- 1) kb_document 增生效日期三元组（effective_date/set_by/set_at）：知识生效日期裁决依据，存量行为 NULL
-- 2) kb_child_chunk 增 content_hash：chunk 内容哈希（完全重复检测），存量行回填 NULL
-- 3) conflict_resolution 增 source/resolution_hint：冲突来源（如 INGEST/SCAN）与建议解决方式
-- 所有列允许 NULL，兼容存量数据；沿用既有迁移风格（ADD COLUMN / CREATE INDEX 不带 IF NOT EXISTS，
-- H2 MODE=MySQL 与 MySQL 8.0 均可执行，方言回归由 RuleMigrationSchemaIT/V19MigrationSchemaTest 覆盖）

ALTER TABLE kb_document ADD COLUMN effective_date DATE;
ALTER TABLE kb_document ADD COLUMN effective_set_by VARCHAR(64);
ALTER TABLE kb_document ADD COLUMN effective_set_at TIMESTAMP;

ALTER TABLE kb_child_chunk ADD COLUMN content_hash VARCHAR(64);
CREATE INDEX idx_child_content_hash ON kb_child_chunk (content_hash);

ALTER TABLE conflict_resolution ADD COLUMN source VARCHAR(32);
ALTER TABLE conflict_resolution ADD COLUMN resolution_hint VARCHAR(16);
