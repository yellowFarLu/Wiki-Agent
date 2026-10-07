-- V24__business_intent.sql: 业务意图识别 + 工具调用（订单查询 / 轨迹查询 / 清关信息生成）
-- 兼容 H2 (MODE=MySQL) 与 MySQL 8：JSON 用 TEXT；软关联不建物理外键。
-- 分层：business_task(业务任务，长期/审计回放) / business_file(文件元数据，长期) /
--       business_idempotency(操作幂等，有过期窗口)。

-- 一次业务意图任务一行；槽位追问期原地更新，意图切换则旧行置 CANCELLED 并新建。
CREATE TABLE IF NOT EXISTS business_task (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    biz_task_id    VARCHAR(64)  NOT NULL,                 -- 对外业务任务ID(UUID)
    user_id        VARCHAR(64)  NOT NULL,
    session_id     VARCHAR(64)  NOT NULL,
    intent         VARCHAR(32)  NOT NULL,                 -- order_query/trajectory_query/customs_generate
    status         VARCHAR(20)  NOT NULL,                 -- IN_PROGRESS/WAITING_SLOT/COMPLETED/FAILED/CANCELLED/ESCALATED_HUMAN
    slots_json     TEXT,                                  -- 槽位快照(含证据戳)
    order_no       VARCHAR(32),                           -- 冗余:便于按单号审计查询
    result_file_id VARCHAR(64),                           -- 清关产物 file_id(可空)
    human_task_id  BIGINT,                                -- 升级转人工关联 human_task(可空)
    trace_id       VARCHAR(64),
    error          VARCHAR(512),
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    completed_at   TIMESTAMP    NULL,
    CONSTRAINT uk_business_task_id UNIQUE (biz_task_id)
);
CREATE INDEX idx_biz_task_user_session ON business_task (user_id, session_id, created_at);
CREATE INDEX idx_biz_task_order ON business_task (order_no);
CREATE INDEX idx_biz_task_trace ON business_task (trace_id);

-- 文件元数据：MAPPING=上传映射表 / CUSTOMS=生成清关。checksum 为内容审计与同用户自然去重(不建全局唯一)。
CREATE TABLE IF NOT EXISTS business_file (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    file_id         VARCHAR(64)  NOT NULL,                -- 对外文件ID(bf-xxxx)
    file_type       VARCHAR(16)  NOT NULL,                -- MAPPING/CUSTOMS
    original_name   VARCHAR(255) NOT NULL,
    storage_path    VARCHAR(512) NOT NULL,                -- 相对路径 data/business-files/...
    content_type    VARCHAR(128),
    row_count       INT          NOT NULL DEFAULT 0,
    mapping_file_id VARCHAR(64),                          -- CUSTOMS 产物:来源映射表 file_id
    biz_task_id     VARCHAR(64),                          -- 软关联 business_task.biz_task_id
    user_id         VARCHAR(64)  NOT NULL,
    checksum        VARCHAR(64),                          -- 文件内容 sha256
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_business_file_id UNIQUE (file_id)
);
CREATE INDEX idx_business_file_task ON business_file (biz_task_id);
CREATE INDEX idx_business_file_mapping ON business_file (mapping_file_id);
CREATE INDEX idx_business_file_content ON business_file (user_id, file_type, checksum);

-- 操作幂等记录（对标 Stripe / IETF Idempotency-Key）：唯一约束原子抢占，过期窗口与文件长期保留解耦。
CREATE TABLE IF NOT EXISTS business_idempotency (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope        VARCHAR(128) NOT NULL,                   -- userId + ":" + endpoint/tool
    idem_key     VARCHAR(128) NOT NULL,                   -- 客户端 Idempotency-Key 或服务端派生键
    request_hash VARCHAR(64)  NOT NULL,                  -- 归一化请求载荷 sha256
    status       VARCHAR(16)  NOT NULL,                   -- IN_PROGRESS/COMPLETED/FAILED
    result_ref   VARCHAR(64),                             -- 结果引用: file_id / biz_task_id
    error        VARCHAR(512),
    biz_task_id  VARCHAR(64),
    trace_id     VARCHAR(64),
    created_at   TIMESTAMP    NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    CONSTRAINT uk_biz_idem_scope_key UNIQUE (scope, idem_key)
);
CREATE INDEX idx_biz_idem_expires ON business_idempotency (expires_at);
