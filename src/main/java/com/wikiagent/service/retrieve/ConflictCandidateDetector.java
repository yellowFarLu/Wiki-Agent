package com.wikiagent.service.retrieve;

import com.wikiagent.application.knowledge.dedup.KeyTokenDiffer;

import java.util.ArrayList;
import java.util.List;

/**
 * ConflictGuard 粗筛：检索侧候选对检测。
 * <p>
 * 对最终候选的代表子块（rep chunk）做 O(k²) 两两余弦相似度计算
 * （k = 最终候选数，≤ finalTopk，量级 ≤20，成本可忽略），
 * 相似度 ≥ {@code coarseThreshold} 后，再过
 * {@link KeyTokenDiffer#hasCriticalDiff(String, String)} 做关键 token 复判；
 * 双条件都满足才返回疑似冲突对的下标对。
 * <p>
 * 向量来源见 Task 7：优先用 Milvus 查询返回的 dense 向量，
 * 取不到则 EmbeddingModel 重 embed 代表子块并走 EmbeddingCacheService。
 */
public class ConflictCandidateDetector {

    /**
     * 代表子块视图。
     *
     * @param childId 子块 ID
     * @param docId   所属文档 ID
     * @param content 子块正文
     * @param score   检索得分（保留字段，当前未参与冲突判定）
     */
    public record RepChunkView(String childId, String docId, String content, double score) {
    }

    /**
     * 检测疑似冲突对。
     *
     * @param chunks          候选代表子块列表，与 {@code vectors} 按下标对齐
     * @param vectors         每个 chunk 对应的 dense 向量（float 数组）
     * @param coarseThreshold 余弦相似度粗筛阈值（如 0.85）
     * @return 疑似对的下标对列表；候选 ≤1 时返回空列表
     */
    public List<int[]> detect(List<RepChunkView> chunks, float[][] vectors, double coarseThreshold) {
        List<int[]> pairs = new ArrayList<>();
        if (chunks == null || chunks.size() <= 1) {
            return pairs;
        }
        int k = chunks.size();
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                double sim = cosine(vectors[i], vectors[j]);
                if (sim >= coarseThreshold
                        && KeyTokenDiffer.hasCriticalDiff(
                                chunks.get(i).content(), chunks.get(j).content())) {
                    pairs.add(new int[]{i, j});
                }
            }
        }
        return pairs;
    }

    /** 计算两 float 向量的余弦相似度；零向量安全返回 0。 */
    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
