package com.wikiagent.infrastructure.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * v1-v6 实施校正：外部中间件条件装配。
 * <p>
 * 本后处理器在配置文件加载之后执行（{@code ConfigDataEnvironmentPostProcessor}
 * 优先级更高），仅处理 DashScope API Key 缺失场景：
 * Key 为空时排除 DashScope 自动装配并打标记，
 * 由 {@link com.wikiagent.infrastructure.llm.DashScopeFallbackConfig} 据标记装配 NoOpEmbeddingModel，
 * 保证上下文可启动（向量检索不可用但不阻断启动）。
 * <p>
 * Redis 自动装配不再排除（redis.enabled 默认 true，开发期必须启动 Redis）。
 */
public class ConditionalInfraEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "wikiagentInfraExcludes";

    /**
     * DashScope API Key 缺失时必须排除的全部自动配置。
     * 实测 1.1.2.0 中所有 DashScope 自动装配在 Bean 实例化期均硬校验 Key
     * （Alibaba BOM 默认开启 spring.ai.model.* ；Chat 装配同样在被实际依赖时失败）。
     * Key 为空时全部排除：ChatModel 由本工程 DashScopeMultiModelFactory 提供
     * （NoOpChatModel 占位），EmbeddingModel 由 DashScopeFallbackConfig 提供。
     */
    private static final String[] DASHSCOPE_KEY_REQUIRED_AUTOCONFIGS = {
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeImageAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeVideoAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeRerankAutoConfiguration"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ClassLoader cl = application.getClassLoader();
        Set<String> toExclude = new LinkedHashSet<>();
        Map<String, Object> markers = new HashMap<>();

        // DashScope API Key 为空（开发/测试默认）时：排除 embedding 自动装配并打标记，
        // 由 DashScopeFallbackConfig 据标记装配 NoOpEmbeddingModel，保证上下文可启动。
        String apiKey = environment.getProperty("spring.ai.dashscope.api-key", "");
        if (apiKey == null || apiKey.isBlank()) {
            for (String className : DASHSCOPE_KEY_REQUIRED_AUTOCONFIGS) {
                if (ClassUtils.isPresent(className, cl)) {
                    toExclude.add(className);
                }
            }
            markers.put("wikiagent.internal.embedding-noop", "true");
        }

        if (toExclude.isEmpty() && markers.isEmpty()) {
            return;
        }

        Set<String> merged = new LinkedHashSet<>(toExclude);
        String existing = environment.getProperty("spring.autoconfigure.exclude");
        if (existing != null && !existing.isBlank()) {
            Arrays.stream(existing.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .forEach(merged::add);
        }

        Map<String, Object> overrides = new HashMap<>(markers);
        overrides.put("spring.autoconfigure.exclude", String.join(",", merged));
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, overrides));
    }
}
