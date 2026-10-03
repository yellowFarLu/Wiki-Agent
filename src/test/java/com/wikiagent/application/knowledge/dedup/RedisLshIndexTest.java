package com.wikiagent.application.knowledge.dedup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis LSH 索引：band 桶存取与 Redis 不可用时的降级行为。
 * <p>
 * 用 Map 支撑的 mock 验证 put→query 命中、remove 后 miss 的完整链路；
 * 异常用例验证所有 Redis 操作降级为空结果/跳过，不抛出（Redis 只做协调，不得阻断入库）。
 */
@ExtendWith(MockitoExtension.class)
class RedisLshIndexTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOps;

    @Mock
    private ValueOperations<String, String> valueOps;

    /** 以内存 Map 支撑 band 桶的 mock，贴近真实 Redis 行为；按需启用 query/remove stub。 */
    private Map<String, Set<String>> stubBandBuckets(boolean withQuery, boolean withRemove) {
        Map<String, Set<String>> buckets = new HashMap<>();
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.add(anyString(), anyString())).thenAnswer(inv -> {
            buckets.computeIfAbsent(inv.getArgument(0), k -> new HashSet<>())
                    .add(inv.getArgument(1));
            return 1L;
        });
        if (withQuery) {
            when(setOps.members(anyString())).thenAnswer(inv ->
                    buckets.getOrDefault(inv.getArgument(0), Set.of()));
        }
        if (withRemove) {
            when(setOps.remove(anyString(), anyString())).thenAnswer(inv -> {
                Set<String> bucket = buckets.get(inv.getArgument(0));
                if (bucket != null) {
                    bucket.remove(inv.getArgument(1));
                }
                return 1L;
            });
        }
        return buckets;
    }

    @Test
    void put后query应命中同band的chunk() {
        stubBandBuckets(true, false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        RedisLshIndex index = new RedisLshIndex(redisTemplate);
        long[] sig = MinHashSignature.of(
                "红富士苹果新鲜上市产地直供口感脆甜欢迎选购本周特惠活动正在进行中数量有限售完即止价格实惠品质保证");

        index.put("chunk-1", sig);

        assertThat(index.query(sig)).contains("chunk-1");
    }

    @Test
    void remove后query应不再命中() {
        stubBandBuckets(true, true);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        RedisLshIndex index = new RedisLshIndex(redisTemplate);
        long[] sig = MinHashSignature.of(
                "红富士苹果新鲜上市产地直供口感脆甜欢迎选购本周特惠活动正在进行中数量有限售完即止价格实惠品质保证");

        index.put("chunk-1", sig);
        index.remove("chunk-1", sig);

        assertThat(index.query(sig)).doesNotContain("chunk-1");
    }

    @Test
    void remove应从全部band桶删除chunkId并清理签名key() {
        stubBandBuckets(false, true);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        RedisLshIndex index = new RedisLshIndex(redisTemplate);
        long[] sig = MinHashSignature.of(
                "红富士苹果新鲜上市产地直供口感脆甜欢迎选购本周特惠活动正在进行中数量有限售完即止价格实惠品质保证");

        index.put("chunk-1", sig);
        // put 必须持久化签名 Base64（供后续仅知 chunkId 时 remove 重算 band key）
        verify(valueOps).set(eq("lsh:sig:chunk-1"), anyString());

        index.remove("chunk-1", sig);
        // 32 个 band 桶全部执行了成员删除，且签名 key 被清理
        verify(setOps, org.mockito.Mockito.times(RedisLshIndex.BANDS)).remove(anyString(), eq("chunk-1"));
        verify(redisTemplate).delete("lsh:sig:chunk-1");
    }

    @Test
    void Redis异常时query应返回空集合不抛出() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenThrow(new RuntimeException("Redis down"));
        RedisLshIndex index = new RedisLshIndex(redisTemplate);

        Set<String> result = index.query(MinHashSignature.of("苹果是5元"));
        assertThat(result).isEmpty();
    }

    @Test
    void Redis异常时put和remove应静默降级不抛出() {
        when(redisTemplate.opsForSet()).thenThrow(new RuntimeException("Redis down"));
        RedisLshIndex index = new RedisLshIndex(redisTemplate);
        long[] sig = MinHashSignature.of("苹果是5元");

        index.put("chunk-1", sig);
        index.remove("chunk-1", sig);
    }

    @Test
    void 空签名不产生任何Redis调用() {
        RedisLshIndex index = new RedisLshIndex(redisTemplate);
        long[] empty = new long[0];

        index.put("chunk-1", empty);
        index.remove("chunk-1", empty);

        assertThat(index.query(empty)).isEmpty();
        verify(redisTemplate, org.mockito.Mockito.never()).opsForSet();
    }
}
