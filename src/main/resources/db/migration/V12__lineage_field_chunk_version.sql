-- V12__lineage_field_chunk_version.sql: 子项目C 血缘修复
-- 1) extracted_field 增来源页码/原文片段（AC-B5 字段可回溯页码+片段）
-- 2) kb_child_chunk 增 version_no/is_active（重解析新版本，旧 chunk 下线保留）

ALTER TABLE extracted_field ADD COLUMN page_no INT;
ALTER TABLE extracted_field ADD COLUMN snippet TEXT;

ALTER TABLE kb_child_chunk ADD COLUMN version_no INT NOT NULL DEFAULT 1;
ALTER TABLE kb_child_chunk ADD COLUMN is_active TINYINT(1) NOT NULL DEFAULT 1;

CREATE INDEX idx_child_doc_active ON kb_child_chunk (doc_id, is_active);
