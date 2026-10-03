package com.wikiagent.service.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * L0 入库精确去重：内容哈希规范化（去空白 + 拉丁小写）后 SHA-256 hex。
 * 规范化等价文本必须得到同一 hash；不同内容必须得到不同 hash。
 */
class ContentHasherTest {

    @Test
    void 空白差异不影响hash() {
        assertEquals(ContentHasher.sha256Normalized("苹果 是 5 元"),
                ContentHasher.sha256Normalized("苹果是5元"));
        assertEquals(ContentHasher.sha256Normalized("苹果\n是\t5  元"),
                ContentHasher.sha256Normalized("苹果是5元"));
    }

    @Test
    void 拉丁大小写差异不影响hash() {
        assertEquals(ContentHasher.sha256Normalized("Apple"),
                ContentHasher.sha256Normalized("apple"));
        assertEquals(ContentHasher.sha256Normalized("Apple Pie"),
                ContentHasher.sha256Normalized("applepie"));
    }

    @Test
    void 不同内容产生不同hash() {
        assertNotEquals(ContentHasher.sha256Normalized("苹果是5元"),
                ContentHasher.sha256Normalized("苹果是6元"));
        assertNotEquals(ContentHasher.sha256Normalized("apple"),
                ContentHasher.sha256Normalized("orange"));
    }

    @Test
    void hash是64位hex字符() {
        String h = ContentHasher.sha256Normalized("hello");
        assertEquals(64, h.length());
        org.junit.jupiter.api.Assertions.assertTrue(h.matches("[0-9a-f]{64}"));
    }
}
