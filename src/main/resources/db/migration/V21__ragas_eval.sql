-- V21: RAGAS 离线评测执行记录与评测用例集（JVM 编排 Python，见 docs/operations/eval-baseline.md §6）
-- 诚实铁律：
--   指标列 NULL 表示该指标无任何成功评分（看板显示"-"，不计 0）；
--   run.status：RUNNING（执行中）/ OK（至少一项指标有值）/ ERROR（脚本失败或全部指标无值）；
--   每次执行插入一行 run + N 行 sample，历史全量保留；(run_id, sample_id) 幂等。

CREATE TABLE IF NOT EXISTS ragas_eval_run (
    id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id               VARCHAR(64)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    judge_model          VARCHAR(64),
    embedding_model      VARCHAR(64),
    endpoint             VARCHAR(256),
    sample_count         INT          NOT NULL DEFAULT 0,
    duration_sec         DOUBLE,
    faithfulness         DOUBLE       NULL,
    answer_relevancy     DOUBLE       NULL,
    context_precision    DOUBLE       NULL,
    context_recall       DOUBLE       NULL,
    factual_correctness  DOUBLE       NULL,
    semantic_similarity  DOUBLE       NULL,
    reason               VARCHAR(1024),
    output_log           TEXT,
    started_at           TIMESTAMP    NULL,
    finished_at          TIMESTAMP    NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ragas_eval_run UNIQUE (run_id)
);

CREATE INDEX idx_ragas_eval_run_status ON ragas_eval_run (status, created_at);

CREATE TABLE IF NOT EXISTS ragas_eval_sample (
    id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id               VARCHAR(64)  NOT NULL,
    sample_id            VARCHAR(128) NOT NULL,
    domain_tag           VARCHAR(64),
    question             TEXT         NOT NULL,
    contexts             TEXT         NOT NULL,
    answer               TEXT         NOT NULL,
    reference            TEXT         NOT NULL,
    faithfulness         DOUBLE       NULL,
    answer_relevancy     DOUBLE       NULL,
    context_precision    DOUBLE       NULL,
    context_recall       DOUBLE       NULL,
    factual_correctness  DOUBLE       NULL,
    semantic_similarity  DOUBLE       NULL,
    errors               TEXT,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ragas_eval_sample UNIQUE (run_id, sample_id)
);

CREATE INDEX idx_ragas_eval_sample_run ON ragas_eval_sample (run_id);
