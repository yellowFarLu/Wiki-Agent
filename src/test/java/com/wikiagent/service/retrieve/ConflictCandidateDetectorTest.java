package com.wikiagent.service.retrieve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConflictGuard 粗筛：检索侧候选对检测。
 * <p>
 * O(k²) 两两余弦（k ≤ finalTopk，量级 ≤20），阈值参数化；
 * 命中相似后再过 {@link com.wikiagent.application.knowledge.dedup.KeyTokenDiffer#hasCriticalDiff}，
 * 双条件都满足才进疑似对。
 */
class ConflictCandidateDetectorTest {

    /** 关键 token 差异文本：金额不同。 */
    private static final String CONTENT_A = "红富士苹果新鲜上市产地直供口感脆甜售价5元每箱";
    /** 与 A 相似但关键 token（金额）不同。 */
    private static final String CONTENT_B = "红富士苹果新鲜上市产地直供口感脆甜售价3元每箱";
    /** 与 A 仅虚词不同（保证 → 保障），非关键差异。 */
    private static final String CONTENT_C = "红富士苹果新鲜上市产地直供口感脆甜售价5元每箱品质保障";

    /** 构造三维单位向量，使 A-B 余弦 0.92，A-C 余弦 0.6。 */
    private static final float[] VEC_A = {1.0f, 0.0f, 0.0f};
    private static final float[] VEC_B = {0.92f, 0.392f, 0.0f};  // sqrt(1-0.92²)≈0.392
    private static final float[] VEC_C = {0.6f, 0.8f, 0.0f};     // sqrt(1-0.6²)=0.8

    /** 粗筛阈值（与 Task 7 侧一致，默认 0.85）。 */
    private static final double THRESHOLD = 0.85;

    private ConflictCandidateDetector detector = new ConflictCandidateDetector();

    @Test
    void 余弦高于阈值且关键token不同时返回该对() {
        List<ConflictCandidateDetector.RepChunkView> chunks = List.of(
                new ConflictCandidateDetector.RepChunkView("c1", "d1", CONTENT_A, 0.95),
                new ConflictCandidateDetector.RepChunkView("c2", "d2", CONTENT_B, 0.93)
        );
        float[][] vectors = {VEC_A, VEC_B};

        List<int[]> pairs = detector.detect(chunks, vectors, THRESHOLD);

        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0)).containsExactly(0, 1);
    }

    @Test
    void 余弦高于阈值但仅虚词不同时不返回非冲突() {
        List<ConflictCandidateDetector.RepChunkView> chunks = List.of(
                new ConflictCandidateDetector.RepChunkView("c1", "d1", CONTENT_A, 0.95),
                new ConflictCandidateDetector.RepChunkView("c3", "d3", CONTENT_C, 0.93)
        );
        float[][] vectors = {VEC_A, VEC_C};
        // 先验证 A-C 的余弦确实是 0.6（低于阈值，但为防向量精度问题再确认）
        double cosineAC = cosine(VEC_A, VEC_C);
        assertThat(cosineAC).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-6));

        List<int[]> pairs = detector.detect(chunks, vectors, THRESHOLD);

        assertThat(pairs).isEmpty();
    }

    @Test
    void 余弦低于阈值时不返回() {
        List<ConflictCandidateDetector.RepChunkView> chunks = List.of(
                new ConflictCandidateDetector.RepChunkView("c1", "d1", CONTENT_A, 0.95),
                new ConflictCandidateDetector.RepChunkView("c3", "d3", CONTENT_C, 0.93)
        );
        float[][] vectors = {VEC_A, VEC_C};

        List<int[]> pairs = detector.detect(chunks, vectors, THRESHOLD);

        assertThat(pairs).isEmpty();
    }

    @Test
    void 候选少于等于1时返回空列表() {
        List<ConflictCandidateDetector.RepChunkView> one = List.of(
                new ConflictCandidateDetector.RepChunkView("c1", "d1", CONTENT_A, 0.95)
        );
        assertThat(detector.detect(one, new float[][]{VEC_A}, THRESHOLD)).isEmpty();

        List<ConflictCandidateDetector.RepChunkView> empty = List.of();
        assertThat(detector.detect(empty, new float[][]{}, THRESHOLD)).isEmpty();
    }

    @Test
    void 多候选中仅部分满足双条件时只返回命中对() {
        // A-B: 0.92 > 0.85, 关键 token 不同 → 命中
        // A-C: 0.60 < 0.85 → 不命中
        // B-C: dot=0.92*0.6+0.392*0.8=0.8656, |B|=|C|=1 → 0.866 < 0.85? 实际约0.866>0.85
        // 但 B 与 C 内容：B=售价3元 C=售价5元保障，有关键差异吗？
        // diff(B,C): B="售价3元每箱", C="售价5元每箱品质保障"
        // 公共前缀到"售价"，之后diff包含3/5和保障，关键token命中数字 → true
        List<ConflictCandidateDetector.RepChunkView> chunks = List.of(
                new ConflictCandidateDetector.RepChunkView("c1", "d1", CONTENT_A, 0.95),
                new ConflictCandidateDetector.RepChunkView("c2", "d2", CONTENT_B, 0.93),
                new ConflictCandidateDetector.RepChunkView("c3", "d3", CONTENT_C, 0.91)
        );
        float[][] vectors = {VEC_A, VEC_B, VEC_C};

        List<int[]> pairs = detector.detect(chunks, vectors, THRESHOLD);

        // A-B 命中（0.92，关键token不同）；B-C 约0.866>0.85且关键token不同（3vs5）也应命中
        // A-C 不命中（0.60）
        assertThat(pairs).hasSize(2);
        assertThat(pairs).anyMatch(p -> p[0] == 0 && p[1] == 1);
        assertThat(pairs).anyMatch(p -> p[0] == 1 && p[1] == 2);
    }

    @Test
    void 相同内容向量余弦为1但关键token无差异时不返回() {
        String same = "完全相同的内容";
        List<ConflictCandidateDetector.RepChunkView> chunks = List.of(
                new ConflictCandidateDetector.RepChunkView("c1", "d1", same, 0.95),
                new ConflictCandidateDetector.RepChunkView("c2", "d2", same, 0.93)
        );
        float[][] vectors = {VEC_A, VEC_A};

        List<int[]> pairs = detector.detect(chunks, vectors, THRESHOLD);

        assertThat(pairs).isEmpty();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
