package com.wikiagent.infrastructure.business;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.business.DialogueState;
import com.wikiagent.domain.business.DialogueStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话状态 Redis 实现：单 key JSON（{@code wikiagent:dialogue-state:{userId}:{sessionId}}），TTL 24h。
 * <p>
 * Redis 不可用时优雅降级为进程内 Map（load 失败返回空/内存副本，save 失败落内存），不阻断对话链路。
 * 状态是「归约态」（可丢弃、可从 chat_history 重建），非审计源。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisDialogueStateStore implements DialogueStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisDialogueStateStore.class);

    private static final String KEY_PREFIX = "wikiagent:dialogue-state:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = buildMapper();
    private final long ttlHours;

    /**
     * 状态专用 mapper。关键取舍：
     * <ul>
     *   <li>只关闭 is-getter 自动发现——record 的 {@code isConfirmed()}/{@code isWaitingSlot()}
     *       会被 Jackson 当成额外只读属性（confirmed/waitingSlot）写出，而它们不是 record 组件，
     *       反序列化必然报 unknown property；record 组件本身的序列化保持默认不受影响。</li>
     *   <li>容忍未知字段——历史版本残留 JSON（confirmed/waitingSlot）可读，向前兼容，
     *       读到时忽略而非整轮状态丢失。</li>
     * </ul>
     */
    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        m.setVisibility(m.getSerializationConfig().getDefaultVisibilityChecker()
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE));
        return m;
    }

    /** Redis 不可用时的进程内降级副本。 */
    private final Map<String, DialogueState> fallback = new ConcurrentHashMap<>();

    public RedisDialogueStateStore(StringRedisTemplate redis,
                                   @Value("${wikiagent.memory.short-term-ttl-hours:24}") long ttlHours) {
        this.redis = redis;
        this.ttlHours = Math.max(1, ttlHours);
    }

    @Override
    public Optional<DialogueState> load(String userId, String sessionId) {
        String key = key(userId, sessionId);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json, DialogueState.class));
        } catch (Exception e) {
            log.warn("对话状态读取 Redis 失败（回退内存）userId={}: {}", userId, e.getMessage());
            return Optional.ofNullable(fallback.get(key));
        }
    }

    @Override
    public boolean save(DialogueState state) {
        String key = key(state.userId(), state.sessionId());
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(state), Duration.ofHours(ttlHours));
            return true;
        } catch (Exception e) {
            log.warn("对话状态写 Redis 失败（落内存降级）userId={}: {}", state.userId(), e.getMessage());
            fallback.put(key, state);
            return true;
        }
    }

    @Override
    public void clear(String userId, String sessionId) {
        String key = key(userId, sessionId);
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("对话状态清除 Redis 失败 userId={}: {}", userId, e.getMessage());
        }
        fallback.remove(key);
    }

    private static String key(String userId, String sessionId) {
        return KEY_PREFIX + userId + ":" + sessionId;
    }
}
