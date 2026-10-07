package com.wikiagent.infrastructure.tool;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Mock 数据的确定性派生工具：同入参（订单号/小包号）必得同输出，字节级可回放，不读外部系统。
 * <p>
 * 派生口径：FNV-1a 64 位哈希 → 从固定列表取状态/城市/国家/品名，时间由哈希派生的固定 epoch 反推。
 * 「未找到」约定：订单号归一化后以 {@code NF} 开头（Not Found），用于确定性测试查无此单路径。
 */
public final class MockDataUtil {

    private MockDataUtil() {}

    /** 订单状态（按序循环）。 */
    static final List<String> STATUSES = List.of("已揽收", "干线运输", "派送中", "已签收");

    /** 城市（始发/目的/轨迹网点）。 */
    static final List<String> CITIES = List.of("深圳", "广州", "上海", "北京", "杭州", "成都", "武汉", "义乌");

    /** 清关目的国。 */
    static final List<String> COUNTRIES = List.of("美国", "英国", "德国", "法国", "日本", "澳大利亚", "加拿大", "新加坡");

    /** 申报品名。 */
    static final List<String> DECLARED_NAMES = List.of("电子产品", "服装", "家居用品", "玩具", "日用品", "配饰");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    /** FNV-1a 64 位哈希（确定性，跨进程一致）。 */
    public static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        if (s == null) {
            return h;
        }
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** 从 seed 哈希稳定取 [0, n) 的索引。 */
    public static int pick(String seed, int n) {
        if (n <= 0) {
            return 0;
        }
        return (int) Math.floorMod(hash(seed), n);
    }

    /** 订单号归一化：去空白、转大写。 */
    public static String normalizeOrderNo(String orderNo) {
        return orderNo == null ? "" : orderNo.trim().toUpperCase();
    }

    /** Mock「未找到」约定：归一化后以 NF 开头。 */
    public static boolean isNotFound(String normalizedOrderNo) {
        return normalizedOrderNo != null && normalizedOrderNo.startsWith("NF");
    }

    /** 按订单号确定性派生订单状态。 */
    public static String orderStatus(String orderNo) {
        return STATUSES.get(pick(orderNo, STATUSES.size()));
    }

    /** 派生始发/目的城市（保证不同）。 */
    public static String originCity(String orderNo) {
        return CITIES.get(pick(orderNo + ":o", CITIES.size()));
    }

    public static String destCity(String orderNo) {
        int o = pick(orderNo + ":o", CITIES.size());
        int d = pick(orderNo + ":d", CITIES.size());
        if (d == o) {
            d = (d + 1) % CITIES.size();
        }
        return CITIES.get(d);
    }

    /** 派生重量（0.2–15.0 kg，一位小数）。 */
    public static double weightKg(String orderNo) {
        return Math.round(((double) Math.floorMod(hash(orderNo + ":w"), 1480) / 10 + 0.2) * 10) / 10.0;
    }

    /** 派生固定 epoch（毫秒），用于订单创建时间与轨迹节点时间的确定性反推。 */
    public static long baseEpoch(String orderNo) {
        return 1750000000000L + Math.floorMod(hash(orderNo + ":t"), 30L * 86400_000L);
    }

    /** epoch → "yyyy-MM-dd HH:mm"（UTC，确定性）。 */
    public static String formatEpoch(long epoch) {
        return TIME_FMT.format(Instant.ofEpochMilli(epoch));
    }

    /** 轨迹节点数（4–6）。 */
    public static int trajectoryNodeCount(String orderNo) {
        return 4 + pick(orderNo + ":n", 3);
    }

    /** 派生清关目的国 / 品名。 */
    public static String country(String smallPackageNo) {
        return COUNTRIES.get(pick(smallPackageNo, COUNTRIES.size()));
    }

    public static String declaredName(String smallPackageNo) {
        return DECLARED_NAMES.get(pick(smallPackageNo, DECLARED_NAMES.size()));
    }

    /** 派生申报重量（0.1–5.0 kg，两位小数）。 */
    public static double declaredWeightKg(String smallPackageNo) {
        return Math.round(((double) Math.floorMod(hash(smallPackageNo + ":cw"), 490) / 10 + 0.1) * 100) / 100.0;
    }

    /** 派生申报价值（1–200，整数）。 */
    public static double declaredValue(String smallPackageNo) {
        return 1 + Math.floorMod(hash(smallPackageNo + ":cv"), 200);
    }
}
