package com.wikiagent.infrastructure.task.redis;

import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Task 6 Redis 协调基础设施（wikiagent.redis.enabled=true）：
 * stream pub/sub 共用的监听容器。
 */
@Configuration
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true")
public class TaskRedisConfig {

    @Bean
    public RedisMessageListenerContainer taskRedisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }

    /**
     * 免密 Redis（本地中间件 / docker-compose 均不设 requirepass、不传 REDIS_PASSWORD）时，
     * spring.data.redis.password 绑定为空串 ""，Redisson 3.45 仍会据此发送 AUTH，
     * 被免密服务端以 "ERR AUTH called without any password configured" 拒绝。
     * 此处把空白密码归一为 null，使 Redisson 跳过 AUTH。
     */
    @Bean
    public RedissonAutoConfigurationCustomizer redissonBlankPasswordCustomizer(
            @Value("${spring.data.redis.password:}") String password) {
        return config -> {
            if (password == null || password.isBlank()) {
                // 本地与 docker-compose 均为单节点 Redis，starter 已初始化 single 配置；
                // useSingleServer() 重复调用返回同一实例，不会覆盖既有连接参数。
                config.useSingleServer().setPassword(null);
            }
        };
    }
}
