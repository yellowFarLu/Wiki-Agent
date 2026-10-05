-- V9__task_framework.sql: 子项目A 任务与数据链基座（规格 §2）
-- 8 张表：任务框架 4 张 + 交接清单 4 张
-- 兼容 H2 (MODE=MySQL) 与 MySQL 8：JSON 列用 TEXT；时间列 TIMESTAMP 由应用显式赋值；索引 V8 风格

-- ============ 任务框架 ============

-- 任务实例（状态机真相源，规格 2.1）
CREATE TABLE IF NOT EXISTS task_instance (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(40)  NOT NULL,
    task_type       VARCHAR(32)  NOT NULL,              -- INGEST / AGENT
    biz_key         VARCHAR(128) NOT NULL,              -- 幂等业务键
    payload         TEXT         NOT NULL,              -- 提交参数 JSON
    status          VARCHAR(16)  NOT NULL,              -- PENDING/DISPATCH/RUNNING/...
    priority        INT          NOT NULL DEFAULT 5,
    attempt         INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 3,
    progress_percent INT         NOT NULL DEFAULT 0,
    result_ref      TEXT,
    error_code      VARCHAR(32),
    error_msg       TEXT,
    idempotency_key VARCHAR(64),
    submitted_by    VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64),
    enqueue_at      TIMESTAMP NULL,
    lease_owner     VARCHAR(64),
    lease_expire_at TIMESTAMP NULL,
    heartbeat_at    TIMESTAMP NULL,
    next_run_at     TIMESTAMP    NOT NULL,
    suspend_reason  VARCHAR(255),
    control_version INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_task_task_id UNIQUE (task_id),
    CONSTRAINT uk_biz_key UNIQUE (biz_key)
);

CREATE INDEX idx_status_next_run ON task_instance (status, next_run_at);
CREATE INDEX idx_lease ON task_instance (status, lease_expire_at);
CREATE INDEX idx_submitter ON task_instance (submitted_by);

-- 任务步骤（规格 2.2）
CREATE TABLE IF NOT EXISTS task_step (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id     VARCHAR(40)  NOT NULL,
    step_no     INT          NOT NULL,
    step_type   VARCHAR(48)  NOT NULL,
    step_name   VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL,                 -- PENDING/RUNNING/DONE/SKIPPED/FAILED
    checkpoint  TEXT,
    started_at  TIMESTAMP NULL,
    ended_at    TIMESTAMP NULL,
    error_msg   TEXT,
    CONSTRAINT uk_task_step UNIQUE (task_id, step_no)
);

CREATE INDEX idx_step_task ON task_step (task_id);

-- 任务事件（追加式，规格 2.3）
CREATE TABLE IF NOT EXISTS task_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id     VARCHAR(40)  NOT NULL,
    event_type  VARCHAR(32)  NOT NULL,
    actor_type  VARCHAR(16)  NOT NULL,                 -- SYSTEM/USER/WORKER
    actor_id    VARCHAR(64),
    detail      TEXT,
    created_at  TIMESTAMP    NOT NULL
);

CREATE INDEX idx_task_event ON task_event (task_id, id);
CREATE INDEX idx_event_type_time ON task_event (event_type, created_at);

-- 人工接管点（规格 2.4）
CREATE TABLE IF NOT EXISTS human_task (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id      VARCHAR(40)  NOT NULL,
    step_no      INT          NOT NULL,
    kind         VARCHAR(16)  NOT NULL,                 -- INPUT / DIRECT_RESOLVE
    title        VARCHAR(128) NOT NULL,
    instruction  TEXT,
    form_schema  TEXT,
    form_value   TEXT,
    status       VARCHAR(16)  NOT NULL,                 -- OPEN/CLAIMED/RESOLVED/EXPIRED
    claimed_by   VARCHAR(64),
    claimed_at   TIMESTAMP NULL,
    resolved_by  VARCHAR(64),
    resolved_at  TIMESTAMP NULL,
    lock_version INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT uk_task_step_kind UNIQUE (task_id, step_no, kind)
);

CREATE INDEX idx_ht_status ON human_task (status);

-- ============ 交接清单（替代 todo.json，规格 2.5） ============

CREATE TABLE IF NOT EXISTS handover_checklist (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id          VARCHAR(64) NOT NULL,
    session_id       VARCHAR(64) NOT NULL,
    original_request TEXT        NOT NULL,
    plan_json        TEXT,
    status           VARCHAR(16),
    version          INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMP   NOT NULL,
    updated_at       TIMESTAMP   NOT NULL,
    CONSTRAINT uk_user_session UNIQUE (user_id, session_id)
);

CREATE TABLE IF NOT EXISTS handover_node (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    checklist_id    BIGINT       NOT NULL,
    node_id         VARCHAR(64)  NOT NULL,
    node_type       VARCHAR(32),
    declared_intent TEXT,
    result          TEXT,
    status          VARCHAR(16),                       -- PENDING/COMPLETED/FAILED/ABANDONED
    trace_id        VARCHAR(64),
    seq             INT,
    created_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_checklist ON handover_node (checklist_id);

CREATE TABLE IF NOT EXISTS handover_abandoned_path (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    checklist_id BIGINT      NOT NULL,
    node_id      VARCHAR(64) NOT NULL,
    reason       TEXT,
    created_at   TIMESTAMP   NOT NULL
);

CREATE INDEX idx_abandoned_checklist ON handover_abandoned_path (checklist_id);

CREATE TABLE IF NOT EXISTS handover_data_ref (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    checklist_id BIGINT       NOT NULL,
    ref_key      VARCHAR(128) NOT NULL,
    ref_value    TEXT,
    updated_at   TIMESTAMP    NOT NULL,
    CONSTRAINT uk_checklist_key UNIQUE (checklist_id, ref_key)
);
