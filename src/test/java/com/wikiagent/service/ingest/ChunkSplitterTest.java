package com.wikiagent.service.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSplitterTest {

    @Test
    void 参数非法应抛异常() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkSplitter(50, 10, 350, 60));
        assertThrows(IllegalArgumentException.class, () -> new ChunkSplitter(1000, 150, 100, 100));
    }

    @Test
    void 短段落应聚合为父块() {
        ChunkSplitter splitter = new ChunkSplitter(100, 10, 50, 10);
        List<String> parents = splitter.splitParents("第一段。\n第二段。\n第三段。");
        assertEquals(1, parents.size());
        assertEquals("第一段。\n第二段。\n第三段。", parents.get(0));
    }

    @Test
    void 超长段落应按句子切分且带重叠() {
        ChunkSplitter splitter = new ChunkSplitter(120, 15, 60, 10);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("句话，用于测试切分逻辑。");
        }
        List<String> parents = splitter.splitParents(sb.toString());
        assertTrue(parents.size() >= 2);
        // 相邻父块应有重叠
        String tail = parents.get(0).substring(Math.max(0, parents.get(0).length() - 10));
        assertTrue(parents.get(1).startsWith(tail) || parents.get(1).contains(tail),
                "相邻父块应共享重叠内容");
    }

    @Test
    void 短父块切子块返回原文本() {
        ChunkSplitter splitter = new ChunkSplitter(1000, 100, 350, 60);
        List<String> children = splitter.splitChildren("短文本。");
        assertEquals(1, children.size());
        assertEquals("短文本。", children.get(0));
    }

    @Test
    void 长父块切子块应覆盖全部内容且尺寸受控() {
        ChunkSplitter splitter = new ChunkSplitter(1000, 100, 120, 30);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("段落").append(i).append("：").append("企业知识库测试内容，用于验证子块滑窗切分。");
        }
        String parent = sb.toString();
        List<String> children = splitter.splitChildren(parent);
        assertTrue(children.size() > 1);
        ChunkSplitter.LengthMeasurer measurer = ChunkSplitter.defaultLengthMeasurer();
        for (String c : children) {
            // 尺寸口径为 token；该语料含双位数字（2 字符≈1 token），字符数允许略大于 120
            assertTrue(measurer.length(c) <= 120, "子块 token 不应超过 120，实际 " + measurer.length(c));
            assertTrue(c.length() <= 135, "子块字符长度异常，实际 " + c.length());
        }
        // 首块从文本开头开始，末块覆盖到文本结尾附近
        assertTrue(parent.startsWith(children.get(0)));
        String last = children.get(children.size() - 1);
        assertTrue(parent.endsWith(last) || parent.contains(last));
        // 相邻子块有重叠（按 token 计量口径，纯中文 token 数≈字符数）
        String head = children.get(1).substring(0, 10);
        assertTrue(children.get(0).contains(head) || children.get(2).contains(head),
                "相邻子块应共享重叠内容");
    }

    @Test
    void 英文文本应按token而非字符收口() {
        // 300 个 "word"（4 字母 ≈ 1 token），空格分隔；子块目标 100 token、0 重叠
        ChunkSplitter splitter = new ChunkSplitter(500, 0, 100, 0);
        String parent = String.join(" ", java.util.Collections.nCopies(300, "word"));
        List<String> children = splitter.splitChildren(parent);
        assertTrue(children.size() > 1);
        // 字符口径下每块最多 100 字符（约 20 词）；token 口径下每块约 100 词（近 500 字符）
        assertTrue(children.get(0).length() > 300,
                "英文子块应按 token 收口，实际首块字符数 " + children.get(0).length());
    }

    @Test
    void 空行段落边界应优先于单换行() {
        // A 段为短段落（3 个短句），B 段为多短行超长段落；父块目标 100 token。
        // 空行优先时首块恰好是 A 段；若错误地先按单换行切，首块会吞进 B 段的行。
        ChunkSplitter splitter = new ChunkSplitter(100, 0, 50, 0);
        String paragraphA = "短行一的内容稍微长一点。\n短行二的内容稍微长一点。\n短行三的内容稍微长一点。";
        StringBuilder doc = new StringBuilder(paragraphA).append("\n\n");
        for (int i = 0; i < 8; i++) {
            doc.append("第二段的企业知识库测试短行内容，大约是三十个字符。\n");
        }
        List<String> parents = splitter.splitParents(doc.toString());
        assertTrue(parents.size() >= 2);
        assertEquals(paragraphA, parents.get(0));
    }

    @Test
    void 超长英文段落应按英文句点切分且不在词中断句() {
        ChunkSplitter splitter = new ChunkSplitter(120, 0, 60, 0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            sb.append("This is sentence number ").append(i).append(". ");
        }
        List<String> parents = splitter.splitParents(sb.toString().strip());
        assertTrue(parents.size() >= 2);
        for (String p : parents) {
            String t = p.strip();
            assertTrue(t.startsWith("This"), "块首应是完整句子开头，实际：" + t.substring(0, Math.min(20, t.length())));
            assertTrue(t.endsWith("."), "块尾应落在句点，实际：" + t.substring(Math.max(0, t.length() - 20)));
        }
    }

    @Test
    void markdown标题应作为章节块的开头() {
        ChunkSplitter splitter = new ChunkSplitter(120, 0, 60, 0);
        // 正文用带换行的短行：旧实现会把标题行与上一节尾部短行聚进同一块
        StringBuilder doc = new StringBuilder("# 文档标题\n## 第一节\n");
        for (int i = 0; i < 8; i++) {
            doc.append("第一节的企业知识库测试短行内容，约三十个字符。\n");
        }
        doc.append("## 第二节\n");
        for (int i = 0; i < 4; i++) {
            doc.append("第二节的企业知识库测试短行内容，约三十个字符。\n");
        }
        List<String> parents = splitter.splitParents(doc.toString());
        String headingChunk = parents.stream()
                .filter(p -> p.contains("第二节"))
                .findFirst().orElseThrow(() -> new AssertionError("应存在包含第二节的块"));
        String head = headingChunk.strip();
        assertTrue(head.startsWith("## 第二节"),
                "章节标题不应粘连上一节的尾部内容，实际块首：" + head.substring(0, Math.min(20, head.length())));
    }

    @Test
    void token估算口径() {
        ChunkSplitter.LengthMeasurer m = ChunkSplitter.defaultLengthMeasurer();
        assertEquals(0, m.length(""));
        assertEquals(1, m.length("word"));                  // 4 字母 ≈ 1 token
        assertEquals(5, m.length("internationalization"));  // 20 字母 ≈ 5 token
        assertEquals(2, m.length("你好"));                   // CJK 1 字 1 token
        assertEquals(1, m.length("，"));                     // 中文标点计入 CJK
        // Hello,(3：alnum5→2 + 逗号1) + 世界(2) = 5
        assertEquals(5, m.length("Hello, 世界"));
        assertEquals(0, m.length("   \n\t "));              // 空白不计
    }
}
