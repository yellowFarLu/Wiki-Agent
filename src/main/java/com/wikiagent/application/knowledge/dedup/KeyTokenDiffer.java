package com.wikiagent.application.knowledge.dedup;

import java.util.regex.Pattern;

/**
 * 关键 token 判定器：判断两段相似文本的差异是否"关键"。
 * <p>
 * 先剥离最长公共前后缀取 diff 区间（a 侧与 b 侧拼接），diff 内命中以下任一即 true：
 * <ul>
 *   <li>数字/小数/百分号（量、价、比率变化）</li>
 *   <li>日期模式（{@code \d{4}[-/年.]} 等）</li>
 *   <li>货币量词（元/万/千/亿）</li>
 *   <li>否定词（不/未/无/非/禁止/不得/取消）</li>
 * </ul>
 * 用于 L1 近重复命中后的二次确认：相似但关键 token 不同 → 不得去重，转冲突裁决。
 */
public final class KeyTokenDiffer {

    private static final Pattern NUMBER = Pattern.compile("[\\d%％]|\\d+\\.\\d+");
    private static final Pattern DATE = Pattern.compile("\\d{4}[-/年.]");
    private static final Pattern CURRENCY = Pattern.compile("[元万千亿]");
    private static final Pattern NEGATION = Pattern.compile("禁止|不得|取消|不|未|无|非");

    private KeyTokenDiffer() {
    }

    /** null/相同文本返回 false；否则对 diff 区间做关键 token 命中检测。 */
    public static boolean hasCriticalDiff(String a, String b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        int prefix = commonPrefixLength(a, b);
        int suffix = commonSuffixLength(a, b, prefix);
        String diff = a.substring(prefix, a.length() - suffix)
                + b.substring(prefix, b.length() - suffix);
        if (diff.isEmpty()) {
            return false;
        }
        return NUMBER.matcher(diff).find()
                || DATE.matcher(diff).find()
                || CURRENCY.matcher(diff).find()
                || NEGATION.matcher(diff).find();
    }

    private static int commonPrefixLength(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /** 最长公共后缀长度，且不与已剥离的前缀区间重叠。 */
    private static int commonSuffixLength(String a, String b, int prefix) {
        int max = Math.min(a.length(), b.length()) - prefix;
        int i = 0;
        while (i < max && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i;
    }
}
