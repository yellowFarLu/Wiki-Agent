-- V22: RAGAS 评测样本 provenance（评估数据三阶段流水线，见 docs/operations/eval-baseline.md §6.2）
-- 每条评分样本标记数据来源/难度/审核状态/答案来源，使看板能区分：
--   seed-manual（人工种子基线）/ synthetic（RAGAS 合成，阶段一）/
--   production（生产日志导出，阶段二）/ expert（专家边界 case，阶段三）
-- 历史行回填为 seed-manual/approved（V21 时代仅有人工基线 12 条）。

ALTER TABLE ragas_eval_sample ADD COLUMN source VARCHAR(32) NULL;
ALTER TABLE ragas_eval_sample ADD COLUMN difficulty VARCHAR(32) NULL;
ALTER TABLE ragas_eval_sample ADD COLUMN review_status VARCHAR(16) NULL;
ALTER TABLE ragas_eval_sample ADD COLUMN answer_origin VARCHAR(32) NULL;
ALTER TABLE ragas_eval_sample ADD COLUMN tags VARCHAR(512) NULL;

UPDATE ragas_eval_sample
SET source = 'seed-manual',
    review_status = 'approved',
    difficulty = 'simple',
    answer_origin = 'manual'
WHERE source IS NULL;
