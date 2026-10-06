package com.wikiagent.infrastructure.observability.circuit;

import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.domain.observability.circuit.CircuitStateSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * redis 组件降级状态：{@code wikiagent.redis.enabled=true} 时协调层
 * （租约/控制标志/进度/stream）运行在 Redis 集群模式，限流亦为分布式令牌桶。
 * <p>
 * <b>诚实声明</b>：运行时 Redis 连接中断的瞬时探测（Redisson 重连状态）
 * 尚未接入，当前恒报 CLOSED，待协调组件暴露运行时健康位后补充。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class RedisDegradedStateSupplier implements CircuitStateSupplier {

    @Override
    public String component() {
        return CircuitComponents.REDIS;
    }

    @Override
    public CircuitState state() {
        return CircuitState.CLOSED;
    }
}
