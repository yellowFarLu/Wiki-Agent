-- V18: 知识看板 RAG 回答质量在线抽样评测（LLM-as-judge，方案见 docs/rag-accuracy-eval.md）
-- 诚实铁律：faithfulness/relevance 为 NULL 表示无法评判（如无引用上下文），不计入指标分母；
-- status 只有三个终态：PENDING（待评判）/ JUDGED（已评判）/ FAILED（重试耗尽）。

CREATE TABLE IF NOT EXISTS rag_answer_eval (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id      VARCHAR(64)   NOT NULL,
    user_id         VARCHAR(64),
    question        TEXT          NOT NULL,
    answer          TEXT          NOT NULL,
    answer_hash     VARCHAR(64)  NOT NULL,
    source_doc_ids  TEXT,
    channel         VARCHAR(32),
    judge_model     VARCHAR(64),
    faithfulness    INT           NULL,
    relevance       INT           NULL,
    verdict_reason  VARCHAR(1024),
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    error           VARCHAR(512),
    attempts        INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    judged_at       TIMESTAMP     NULL,
    CONSTRAINT uk_rag_answer_eval UNIQUE (session_id, answer_hash)
);

CREATE INDEX idx_rag_answer_eval_status ON rag_answer_eval (status, created_at);
