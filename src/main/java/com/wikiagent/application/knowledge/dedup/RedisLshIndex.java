package com.wikiagent.application.knowledge.dedup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * L1 近重复检测：Redis 上的 MinHash LSH 索引（32 bands × 4 rows = 128 perm）。
 * <p>
 * key 结构：
 * <ul>
 *   <li>{@code lsh:band:{bandIdx}:{bandHash}} — Set 存 chunkId；bandHash 为每带 4 个 long
 *       的 {@code Arrays.hashCode}，同 band 同桶即候选近重复</li>
 *   <li>{@code lsh:sig:{chunkId}} — String 存签名 Base64，供 remove 重算 band key</li>
 * </ul>
 * Redis 只做协调、不是状态真相源：所有操作包 try/catch，异常降级为空结果/跳过并 warn，
 * 不得阻断入库（全局约束）。
 */
public class RedisLshIndex {

    private static final Logger log = LoggerFactory.getLogger(RedisLshIndex.class);

    public static final int BANDS = 32;
    public static final int ROWS_PER_BAND = 4;

    private static final String BAND_KEY_PREFIX = "lsh:band:";
    private static final String SIG_KEY_PREFIX = "lsh:sig:";

    private final StringRedisTemplate redis;

    public RedisLshIndex(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 查询候选近重复 chunkId 集合；空签名直接返回空，Redis 异常降级为空集合。 */
    public Set<String> query(long[] sig) {
        if (sig == null || sig.length == 0) {
            return Set.of();
        }
        try {
            Set<String> out = new HashSet<>();
            for (int b = 0; b < BANDS; b++) {
                Set<String> members = redis.opsForSet().members(bandKey(b, bandHash(sig, b)));
                if (members != null) {
                    out.addAll(members);
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("LSH query 失败，降级为空结果（不阻断入库）: {}", e.getMessage());
            return Set.of();
        }
    }

    /** 写入 chunk 的 32 个 band 桶并持久化签名 Base64；Redis 异常降级跳过并 warn。 */
    public void put(String chunkId, long[] sig) {
        if (sig == null || sig.length == 0) {
            return;
        }
        try {
            for (int b = 0; b < BANDS; b++) {
                redis.opsForSet().add(bandKey(b, bandHash(sig, b)), chunkId);
            }
            redis.opsForValue().set(SIG_KEY_PREFIX + chunkId, encode(sig));
        } catch (RuntimeException e) {
            log.warn("LSH put 失败，降级跳过: chunkId={}, {}", chunkId, e.getMessage());
        }
    }

    /** 按签名重算 band key 并移除 chunkId、清理签名 key；Redis 异常降级跳过并 warn。 */
    public void remove(String chunkId, long[] sig) {
        if (sig == null || sig.length == 0) {
            return;
        }
        try {
            for (int b = 0; b < BANDS; b++) {
                redis.opsForSet().remove(bandKey(b, bandHash(sig, b)), chunkId);
            }
            redis.delete(SIG_KEY_PREFIX + chunkId);
        } catch (RuntimeException e) {
            log.warn("LSH remove 失败，降级跳过: chunkId={}, {}", chunkId, e.getMessage());
        }
    }

    private static String bandKey(int band, int bandHash) {
        return BAND_KEY_PREFIX + band + ":" + bandHash;
    }

    /** 每带 4 个 long 做 Arrays.hashCode 作为桶 hash。 */
    private static int bandHash(long[] sig, int band) {
        int from = band * ROWS_PER_BAND;
        int to = Math.min(from + ROWS_PER_BAND, sig.length);
        return Arrays.hashCode(Arrays.copyOfRange(sig, from, to));
    }

    private static String encode(long[] sig) {
        ByteBuffer buf = ByteBuffer.allocate(sig.length * Long.BYTES);
        for (long v : sig) {
            buf.putLong(v);
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }
}
