package com.wikiagent.infrastructure.parse.dashscope;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.wikiagent.domain.parse.spi.ParseProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.List;

/**
 * DashScope qwen-vl 多模态视觉客户端（OCR/LAYOUT/TABLE 共用）。
 * 经 Spring AI 已验证 API：UserMessage + Media(ByteArrayResource)；要求模型只回 JSON。
 * 无 API Key 或模型构建失败时 {@link #available()} 为 false，不阻断应用启动。
 */
public class DashScopeVisionClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeVisionClient.class);

    private final String model;
    private volatile ChatModel chatModel;
    private volatile boolean initFailed;

    public DashScopeVisionClient(String apiKey, String model) {
        this.model = model;
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                DashScopeApi api = DashScopeApi.builder().apiKey(apiKey.trim()).build();
                this.chatModel = DashScopeChatModel.builder()
                        .dashScopeApi(api)
                        // multiModel=true 才会路由到 /multimodal-generation/generation；
                        // 缺省 false 会把图片消息发到 text-generation 端点，服务端报
                        // "url error, please check url！"（错误码文档 #error-url 原因一）
                        .defaultOptions(DashScopeChatOptions.builder()
                                .model(model)
                                .multiModel(true)
                                .build())
                        .build();
            } catch (Exception e) {
                this.initFailed = true;
                log.warn("qwen-vl ChatModel 构建失败，视觉能力不可用: {}", e.getMessage());
            }
        }
    }

    public boolean available() {
        return chatModel != null && !initFailed;
    }

    /** 发送图片 + 任务指令，返回模型文本（约定为 JSON）。 */
    public String visionJson(byte[] imageBytes, String mimeType, String instruction) {
        if (!available()) {
            throw new ParseProviderException("视觉模型未配置/不可用: " + model, false);
        }
        try {
            MimeType type = parseMime(mimeType);
            Media media = new Media(type, new ByteArrayResource(imageBytes));
            UserMessage message = UserMessage.builder()
                    .text(instruction)
                    .media(media)
                    .build();
            ChatResponse response = chatModel.call(new Prompt(List.of(message)));
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw new ParseProviderException("视觉模型返回空响应: " + model, true);
            }
            return stripCodeFence(text);
        } catch (ParseProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseProviderException("视觉模型调用失败: " + e.getMessage(), true, e);
        }
    }

    private static MimeType parseMime(String mimeType) {
        try {
            return mimeType == null || mimeType.isBlank()
                    ? MimeTypeUtils.IMAGE_PNG : MimeTypeUtils.parseMimeType(mimeType);
        } catch (Exception e) {
            return MimeTypeUtils.IMAGE_PNG;
        }
    }

    /** 模型偶尔包裹 ```json 代码围栏，剥掉后再交 JSON 解析。 */
    static String stripCodeFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) {
                t = t.substring(firstNl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }
}
