package com.wikiagent.application.knowledge;

/**
 * 疑似过期知识评分（自定义启发式，非业界标准指标，如实命名）。
 * <p>
 * staleScore = 0.5 * exp(-ageDays / tauTimeDays) + 0.5 * min(1, recentRetrievalCount / 5)
 * 时间衰减常数默认 180 天；频率参考窗口取近 30 天检索次数。
 * 分值越低越疑似过期；看板与聚合作业共用本类保证同一口径。
 */
public final class StaleScore {

    private StaleScore() {
    }

    /**
     * @param ageDays      知识创建至今的天数；{@code Long.MAX_VALUE} 表示创建时间缺失，时间衰减按 0 计
     * @param recentCount  近 30 天该 chunk 的检索次数
     * @param tauTimeDays  时间衰减常数（天）
     * @return [0,1] 区间的新鲜度分值，越低越疑似过期
     */
    public static double score(long ageDays, long recentCount, long tauTimeDays) {
        double timeDecay = ageDays >= Long.MAX_VALUE ? 0.0 : Math.exp(-(double) ageDays / tauTimeDays);
        double freqScore = Math.min(1.0, recentCount / 5.0);
        return 0.5 * timeDecay + 0.5 * freqScore;
    }
}
