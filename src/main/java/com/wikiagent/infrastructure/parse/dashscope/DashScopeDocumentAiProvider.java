package com.wikiagent.infrastructure.parse.dashscope;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.spi.AudioRequest;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.DocumentAiProvider;
import com.wikiagent.domain.parse.spi.LayoutRequest;
import com.wikiagent.domain.parse.spi.LayoutResult;
import com.wikiagent.domain.parse.spi.OcrRequest;
import com.wikiagent.domain.parse.spi.OcrResult;
import com.wikiagent.domain.parse.spi.TableRequest;
import com.wikiagent.domain.parse.spi.TableResult;
import com.wikiagent.domain.parse.spi.TranscriptResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * DashScope 文档 AI 供应商（子项目 B 首批）：
 * OCR/LAYOUT/TABLE 走 qwen-vl 多模态；ASR 走 Paraformer 文件转写 REST。
 * 无 API Key 时 capabilities 为空、available=false，流水线自动跳过 AI 能力。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.parse.provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeDocumentAiProvider implements DocumentAiProvider {

    private final ParseProperties props;
    private final DashScopeVisionClient vision;
    private final DashScopeAudioTranscriptClient audio;

    public DashScopeDocumentAiProvider(ParseProperties props,
                                       @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.props = props;
        this.vision = new DashScopeVisionClient(apiKey, props.getVisionModel());
        this.audio = new DashScopeAudioTranscriptClient(apiKey, props.getDashscopeBaseUrl(), props.getAsrModel());
    }

    @Override
    public String name() {
        return "dashscope";
    }

    @Override
    public Set<Capability> capabilities() {
        if (!available()) {
            return Set.of();
        }
        Set<Capability> caps = EnumSet.of(Capability.OCR, Capability.LAYOUT, Capability.TABLE);
        // ASR 额外要求公网文件 URL 基础配置
        if (props.getAsrFileBaseUrl() != null && !props.getAsrFileBaseUrl().isBlank()) {
            caps.add(Capability.ASR);
        }
        return caps;
    }

    @Override
    public boolean available() {
        return vision.available();
    }

    @Override
    public OcrResult ocr(OcrRequest request) {
        // 只取 fullText/confidence：spans/bbox 全链路无消费者，要求它们会让输出 token 翻倍、
        // 密集页在 qwen-vl-max 上超 60s 必触发 30s 超时（实测 68s vs 精简后 18s）
        String json = vision.visionJson(request.imageBytes(), request.mimeType(), """
                对图片做 OCR 识别，只输出 JSON，不要输出任何解释或 Markdown：
                {"fullText":"整页识别文本","confidence":0.0到1.0}
                """);
        return DashScopeVisionResultParser.parseOcr(json, request.pageNo());
    }

    @Override
    public TranscriptResult transcribe(AudioRequest request) {
        String url = request.publicUrl();
        if ((url == null || url.isBlank())
                && props.getAsrFileBaseUrl() != null && !props.getAsrFileBaseUrl().isBlank()) {
            url = props.getAsrFileBaseUrl().replaceAll("/$", "") + "/" + request.filename();
        }
        return audio.transcribe(url, props.getAsrTimeoutSec());
    }

    @Override
    public LayoutResult layout(LayoutRequest request) {
        String json = vision.visionJson(request.imageBytes(), request.mimeType(), """
                分析页面版面，只输出 JSON，不要输出任何解释或 Markdown：
                {"blocks":[{"order":阅读顺序整数,"type":"TITLE或TEXT或TABLE或FIGURE或HEADER或FOOTER或PAGE_NUMBER或FORMULA或CAPTION","text":"块内文本","bbox":[x,y,w,h]}]}
                """);
        return DashScopeVisionResultParser.parseLayout(json);
    }

    @Override
    public TableResult table(TableRequest request) {
        String json = vision.visionJson(request.imageBytes(), request.mimeType(), """
                识别图片中的表格结构（注意跨行列合并单元格），只输出 JSON，不要输出任何解释或 Markdown：
                {"rows":总行数,"cols":总列数,"cells":[{"row":0基行号,"col":0基列号,"rowSpan":跨行默认1,"colSpan":跨列默认1,"text":"单元格文本","bbox":[x,y,w,h]}]}
                """);
        return DashScopeVisionResultParser.parseTable(json, request.pageNo());
    }
}
