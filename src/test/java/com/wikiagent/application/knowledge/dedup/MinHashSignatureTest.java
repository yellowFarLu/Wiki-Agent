package com.wikiagent.application.knowledge.dedup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 近重复检测：MinHash 签名。
 * <p>
 * 128 perm 的 MinHash 应使约 50 字正文差 1 字的版本 jaccard ≥ 0.9，
 * 完全不同文本 jaccard < 0.3，空/单字文本安全返回空签名（Review Focus #3）。
 */
class MinHashSignatureTest {

    /** 49 个 CJK 字符正文。 */
    private static final String BASE =
            "红富士苹果新鲜上市产地直供口感脆甜欢迎选购本周特惠活动正在进行中数量有限售完即止价格实惠品质保证";
    /** 与 BASE 仅差 1 字（保证 → 保障）。 */
    private static final String ONE_CHAR_DIFF =
            "红富士苹果新鲜上市产地直供口感脆甜欢迎选购本周特惠活动正在进行中数量有限售完即止价格实惠品质保障";

    @Test
    void 约50字正文差1字Jaccard应大于等于0点9() {
        long[] sigA = MinHashSignature.of(BASE);
        long[] sigB = MinHashSignature.of(ONE_CHAR_DIFF);
        double jaccard = MinHashSignature.jaccard(sigA, sigB);
        assertThat(jaccard).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void 完全不同文本Jaccard应小于0点3() {
        long[] sigA = MinHashSignature.of(BASE);
        long[] sigB = MinHashSignature.of("量子计算机通过叠加态实现并行计算，与经典计算机架构完全不同。");
        double jaccard = MinHashSignature.jaccard(sigA, sigB);
        assertThat(jaccard).isLessThan(0.3);
    }

    @Test
    void 相同文本Jaccard应为1() {
        long[] sigA = MinHashSignature.of(BASE);
        long[] sigB = MinHashSignature.of(BASE);
        assertThat(MinHashSignature.jaccard(sigA, sigB)).isEqualTo(1.0);
    }

    @Test
    void 空文本返回空签名不抛异常() {
        assertThat(MinHashSignature.of("")).isEmpty();
        assertThat(MinHashSignature.of(null)).isEmpty();
        assertThat(MinHashSignature.of("   ")).isEmpty();
    }

    @Test
    void 单字文本返回空签名不抛异常() {
        assertThat(MinHashSignature.of("苹")).isEmpty();
    }

    @Test
    void 双空签名Jaccard视为1单空视为0() {
        assertThat(MinHashSignature.jaccard(new long[0], new long[0])).isEqualTo(1.0);
        assertThat(MinHashSignature.jaccard(MinHashSignature.of(BASE), new long[0])).isEqualTo(0.0);
    }

    @Test
    void 签名长度应为128() {
        assertThat(MinHashSignature.of(BASE)).hasSize(128);
    }
}
