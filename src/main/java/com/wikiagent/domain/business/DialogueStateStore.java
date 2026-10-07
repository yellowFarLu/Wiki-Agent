package com.wikiagent.domain.business;

import java.util.Optional;

/**
 * 对话状态存储端口（通用，与业务解耦）。
 * <ul>
 *   <li>Redis 实现：单 key JSON，WATCH/version 乐观锁，TTL 24h；</li>
 *   <li>Redis 关闭：进程内 Map 降级；</li>
 *   <li>状态缺失：靠 chat_history + IntentSpec 重建（网关兜底）。</li>
 * </ul>
 */
public interface DialogueStateStore {

    Optional<DialogueState> load(String userId, String sessionId);

    /** 保存状态；返回是否成功（乐观锁冲突/存储不可用时 false，调用方据此判定）。 */
    boolean save(DialogueState state);

    void clear(String userId, String sessionId);
}
