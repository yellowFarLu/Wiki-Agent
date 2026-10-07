package com.wikiagent.infrastructure.business;

import com.wikiagent.domain.business.DialogueState;
import com.wikiagent.domain.business.DialogueStateStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话状态内存实现（Redis 关闭时的降级存储）。
 * <p>
 * 进程内 Map：单机可用，服务重启即丢（由网关靠 chat_history + IntentSpec 重建）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "false")
public class InMemoryDialogueStateStore implements DialogueStateStore {

    private final Map<String, DialogueState> store = new ConcurrentHashMap<>();

    @Override
    public Optional<DialogueState> load(String userId, String sessionId) {
        return Optional.ofNullable(store.get(key(userId, sessionId)));
    }

    @Override
    public boolean save(DialogueState state) {
        store.put(key(state.userId(), state.sessionId()), state);
        return true;
    }

    @Override
    public void clear(String userId, String sessionId) {
        store.remove(key(userId, sessionId));
    }

    private static String key(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }
}
