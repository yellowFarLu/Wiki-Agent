-- V20：对话历史表（开发期由 H2 ddl-auto=update 自动建立，Flyway 迁移遗漏；
--      生产 JPA validate 模式下补建。实体：ChatHistoryEntity）
CREATE TABLE IF NOT EXISTS chat_history (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL,
    role        VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
    content     TEXT         NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    KEY idx_chat_history_session (session_id)
);
