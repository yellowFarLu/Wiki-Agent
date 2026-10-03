package com.wikiagent.application.knowledge.dedup;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * L1 近重复检测：MinHash 签名（128 perm）。
 * <p>
 * shingle = CJK 字符 bigram + 拉丁/数字词（切词思路与 RetrievalService 一致，独立实现）。
 * 每个 shingle 取 64 位哈希后，用 128 组固定随机种子的置换族 {@code (a*x+b) mod (2^61-1)}
 * 取最小值构成签名。种子固定（非每次启动随机）：签名必须跨进程、跨时间可比较，
 * 否则无法与 Redis 中历史签名做 LSH 分桶。
 * <p>
 * 空/单字文本返回 length=0 的空签名，调用方应跳过 LSH 流程。
 */
public final class MinHashSignature {

    public static final int PERMUTATIONS = 128;

    /** Mersenne 素数 2^61-1：置换族在其上取模，可用移位折叠避免大数运算。 */
    private static final long PRIME = (1L << 61) - 1;

    private static final long[] SEED_A = new long[PERMUTATIONS];
    private static final long[] SEED_B = new long[PERMUTATIONS];

    static {
        // 固定种子生成 128 组 (a, b)：a ∈ [1, p)，b ∈ [0, p)
        SplittableRandom rng = new SplittableRandom(0x5EED5EED5EED5EEL);
        for (int i = 0; i < PERMUTATIONS; i++) {
            SEED_A[i] = 1 + Math.floorMod(rng.nextLong(), PRIME - 1);
            SEED_B[i] = Math.floorMod(rng.nextLong(), PRIME);
        }
    }

    private MinHashSignature() {
    }

    /** 计算文本的 128 维 MinHash 签名；空/单字文本返回 length=0 的空签名。 */
    public static long[] of(String text) {
        Set<String> shingles = extractShingles(text);
        if (shingles.isEmpty()) {
            return new long[0];
        }
        long[] sig = new long[PERMUTATIONS];
        Arrays.fill(sig, Long.MAX_VALUE);
        for (String shingle : shingles) {
            long h = hash64(shingle);
            for (int i = 0; i < PERMUTATIONS; i++) {
                long v = mulAddMod(SEED_A[i], h, SEED_B[i]);
                if (v < sig[i]) {
                    sig[i] = v;
                }
            }
        }
        return sig;
    }

    /**
     * 两个签名的 Jaccard 相似度估计 = 相等槽位占比。
     * 双空签名视为完全相同（1.0）；一侧为空视为 0.0。
     */
    public static double jaccard(long[] a, long[] b) {
        if (a == null || b == null) {
            return a == b ? 1.0 : 0.0;
        }
        if (a.length == 0 || b.length == 0) {
            return a.length == b.length ? 1.0 : 0.0;
        }
        int n = Math.min(a.length, b.length);
        int eq = 0;
        for (int i = 0; i < n; i++) {
            if (a[i] == b[i]) {
                eq++;
            }
        }
        return (double) eq / n;
    }

    /** CJK bigram + 拉丁/数字词（≥2 字符，小写）。独立实现，不依赖 RetrievalService。 */
    static Set<String> extractShingles(String text) {
        if (text == null) {
            return Set.of();
        }
        String trimmed = text.trim();
        if (trimmed.length() < 2) {
            return Set.of();
        }
        LinkedHashSet<String> shingles = new LinkedHashSet<>();
        // 拉丁/数字词
        for (String w : trimmed.split("[^A-Za-z0-9_]+")) {
            if (w.length() >= 2) {
                shingles.add(w.toLowerCase(Locale.ROOT));
            }
        }
        // CJK 字符 bigram
        char[] chars = trimmed.toCharArray();
        for (int i = 0; i + 1 < chars.length; i++) {
            if (isCjk(chars[i]) && isCjk(chars[i + 1])) {
                shingles.add(new String(chars, i, 2));
            }
        }
        return shingles;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /** FNV-1a 64 + Murmur3 fmix64 收尾保证雪崩，最后折到 [0, p) 域上。 */
    private static long hash64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return Math.floorMod(h, PRIME);
    }

    /**
     * {@code (a*x + b) mod (2^61-1)}。利用 Mersenne 素数性质 2^61 ≡ 1 (mod p) 折叠
     * 128 位乘积，避免 BigInteger（128 perm × N shingle 的热路径）。
     */
    private static long mulAddMod(long a, long x, long b) {
        long hi = Math.multiplyHigh(a, x);
        long lo = a * x;
        // hi*2^64 ≡ hi*8 (mod p)；lo 拆成高 3 位与低 61 位分别折叠
        long folded = (hi << 3) + (lo >>> 61) + (lo & PRIME);
        folded = (folded & PRIME) + (folded >>> 61);
        folded += b;
        folded = (folded & PRIME) + (folded >>> 61);
        return folded >= PRIME ? folded - PRIME : folded;
    }
}
