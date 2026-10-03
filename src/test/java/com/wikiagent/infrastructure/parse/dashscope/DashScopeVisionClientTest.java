package com.wikiagent.infrastructure.parse.dashscope;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：qwen-vl 视觉调用必须打开 multiModel，spring-ai-alibaba 才会把请求路由到
 * /multimodal-generation/generation；缺省会走 text-generation 端点并被服务端拒绝
 * （"url error, please check url！"，错误码文档 #error-url 原因一）。
 */
class DashScopeVisionClientTest {

    @Test
    void enablesMultiModelRoutingForVisionCalls() {
        DashScopeVisionClient client = new DashScopeVisionClient("sk-test-dummy", "qwen-vl-max");

        assertThat(client.available()).isTrue();
        DashScopeChatModel chatModel =
                (DashScopeChatModel) ReflectionTestUtils.getField(client, "chatModel");
        DashScopeChatOptions options =
                (DashScopeChatOptions) ReflectionTestUtils.getField(chatModel, "defaultOptions");

        assertThat(options.getModel()).isEqualTo("qwen-vl-max");
        assertThat(options.getMultiModel())
                .as("视觉请求必须 multiModel=true，否则图片消息被发往 text-generation 端点报 url error")
                .isTrue();
    }

    @Test
    void unavailableWhenApiKeyMissing() {
        assertThat(new DashScopeVisionClient("  ", "qwen-vl-max").available()).isFalse();
    }
}
