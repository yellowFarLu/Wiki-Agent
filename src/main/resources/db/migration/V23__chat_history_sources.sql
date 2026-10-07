-- V23：对话历史增加引用来源列。
-- assistant 回答的检索来源（JSON 数组，结构同 SSE sources 事件）随消息持久化，
-- 历史会话重开时还原正文 [n] 角标与来源列表；user/blocked 消息为 NULL。
-- 实体：ChatHistoryEntity#sourcesJson
ALTER TABLE chat_history
    ADD COLUMN sources_json TEXT NULL COMMENT 'assistant 回答引用来源 JSON 数组' AFTER content;
