package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.model.ParsedPage;
import com.wikiagent.domain.parse.spi.OcrResult;
import com.wikiagent.domain.parse.spi.OcrSpan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3 文本层/OCR 冲突检测：相似不冲突保留文本层；低相似 CONFLICT 且按 OCR 置信度选文本。
 */
class PageConflictDetectorTest {

    private PageConflictDetector detector(double threshold) {
        ParseProperties p = new ParseProperties();
        p.setTextConflictSimilarity(threshold);
        return new PageConflictDetector(p);
    }

    private OcrResult ocr(String text, double confidence) {
        return new OcrResult(text, List.of(new OcrSpan(text, confidence)), confidence);
    }

    @Test
    void noOcrReturnsPlainTextPage() {
        ParsedPage page = detector(0.85).reconcile(1, "文本层", null);
        assertThat(page.conflict()).isFalse();
        assertThat(page.text()).isEqualTo("文本层");
        assertThat(page.ocr()).isNull();
    }

    @Test
    void similarOcrKeepsTextLayerWithoutConflict() {
        ParsedPage page = detector(0.85).reconcile(2,
                "发票号码：12345678", ocr("发票号码 12345678", 0.9));
        assertThat(page.conflict()).isFalse();
        assertThat(page.text()).isEqualTo("发票号码：12345678");
        assertThat(page.textLayerText()).isEqualTo("发票号码：12345678");
        assertThat(page.diffRate()).isLessThan(0.15);
    }

    @Test
    void divergentResultsFlagConflictAndHighConfidenceOcrWins() {
        ParsedPage page = detector(0.85).reconcile(3,
                "锟斤拷锟斤拷烫烫烫乱码内容XYZ", ocr("合同编号 HT-2026-0098 甲方乙方", 0.95));
        assertThat(page.conflict()).isTrue();
        assertThat(page.text()).isEqualTo("合同编号 HT-2026-0098 甲方乙方");
        assertThat(page.ocr()).isNotNull();
    }

    @Test
    void conflictWithLowConfidenceOcrKeepsTextLayerForHumanReview() {
        ParsedPage page = detector(0.85).reconcile(4,
                "原始文本层内容Alpha", ocr("完全不同的识别结果Beta", 0.5));
        assertThat(page.conflict()).isTrue();
        assertThat(page.text()).isEqualTo("原始文本层内容Alpha");
    }

    @Test
    void garbledHeuristicOnlyTriggersOnSuspiciousText() {
        assertThat(PageConflictDetector.looksGarbled("正常中文与 English 123 混合文本")).isFalse();
        assertThat(PageConflictDetector.looksGarbled("短")).isFalse();
        String garbled = "锟斤拷\u0000烫烫烫\u0000乱码\u0000内容\u0000测试\u0000数据\u0000ABC".repeat(2);
        assertThat(PageConflictDetector.looksGarbled(garbled)).isTrue();
    }

    @Test
    void scatteredNulArtifactsFromFontMappingAreNotGarbled() {
        // 真实案例：课件 PDF 内嵌字体 cmap 缺陷，3047 字符中散点分布 310 个 U+0000（10.2%），
        // 文本层内容完全可读，不应触发 OCR 双跑
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("page_content=3、实验 ");
        }
        String readable = sb.toString();
        String withNulArtifacts = readable.replace(" ", "\u0000");
        assertThat(withNulArtifacts.chars().filter(c -> c == '\u0000').count()).isEqualTo(100);
        assertThat(PageConflictDetector.looksGarbled(withNulArtifacts)).isFalse();
    }

    @Test
    void denseNulRatioStillCountsAsGarbled() {
        // U+0000 占比 ≥25% 才视为乱码（字体映射彻底损坏、文本层不可信的场景）
        String denseNul = "锟斤拷\u0000烫烫烫\u0000乱码\u0000内容\u0000测试\u0000数据\u0000ABC".repeat(2);
        assertThat(PageConflictDetector.looksGarbled(denseNul)).isTrue();
    }

    @Test
    void fffdReplacementCharStillTriggersAtLowThreshold() {
        // U+FFFD 是真乱码信号，保持 10% 阈值
        String fffd = "正常文本内容替换字符测试数据ABCDE".replace("换", "\uFFFD");
        assertThat(PageConflictDetector.looksGarbled(fffd)).isFalse();
        String heavyFffd = "正常文\uFFFD内容\uFFFD换字符\uFFFD试数据\uFFFDBCDE";
        assertThat(PageConflictDetector.looksGarbled(heavyFffd)).isTrue();
    }
}
