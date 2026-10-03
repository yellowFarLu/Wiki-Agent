package com.wikiagent.service.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * L0 入库精确去重：chunk 内容哈希。
 * <p>
 * 规范化规则：去除全部空白字符 {@code \\s+}、拉丁字母转小写（Locale.ROOT），
 * 然后 SHA-256 hex（64 字符小写）。结果写入 {@code kb_child_chunk.content_hash}，
 * 由 {@code KbChildChunkRepo.findFirstByContentHashAndActiveTrue} 命中即视为完全重复。
 */
public final class ContentHasher {

    private ContentHasher() {
    }

    /** 规范化 + SHA-256 hex。null/空串按空串处理（仍返回固定 hash）。 */
    public static String sha256Normalized(String text) {
        String normalized = normalize(text);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 Java 平台必备算法，永远不会到此；防御性抛 IllegalState
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 规范化：去全部空白 + 拉丁小写（Unicode 安全 lowercase）。 */
    static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String noWs = text.replaceAll("\\s+", "");
        return noWs.toLowerCase(Locale.ROOT);
    }
}
