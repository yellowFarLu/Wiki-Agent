package com.wikiagent.infrastructure.observability.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.RetryableTaskException;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.domain.task.TaskHandler;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-I2 集成测试：/actuator/prometheus 含 wikiagent_* 任务/模型/熔断指标与 HikariCP 连接池指标，
 * 桩模型调用后 Counter 增长，任务完成后 duration Timer 落样本。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class ObservabilityMetricsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-obs-metrics-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @TestConfiguration
    static class MetricsHandlerConfig {
        @Bean
        TaskHandler obsMetricsHandler() {
            return new TaskHandler() {
                @Override
                public String taskType() {
                    return "OBS_METRICS";
                }

                @Override
                public List<StepDef> planSteps(JsonNode payloadArgs) {
                    return List.of(StepDef.of(1, "METRICS", "指标任务"));
                }

                @Override
                public StepResult executeStep(TaskExecutionContext ctx)
                        throws RetryableTaskException, FatalTaskException, HumanRequiredException {
                    return StepResult.done(100);
                }
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ModelCallRecorder recorder;

    @Autowired
    private TaskRepositoryPort taskRepo;

    @Test
    void prometheus文本含任务积压熔断状态与HikariCP指标() throws Exception {
        String body = scrapePrometheus();

        assertThat(body).contains("wikiagent_task_backlog");
        assertThat(body).contains("status=\"PENDING\"");
        assertThat(body).contains("wikiagent_circuit_state");
        assertThat(body).contains("component=\"milvus\"");
        assertThat(body).contains("component=\"llm\"");
        // HikariCP micrometer 自动注册
        assertThat(body).contains("hikaricp_connections");
    }

    @Test
    void 桩模型调用后calls_tokens_cost计数增长() {
        double before = meterRegistry.find("wikiagent.model.calls.total")
                .tag("provider", "obs-it").counters().stream()
                .mapToDouble(c -> c.count()).sum();
        double costBefore = meterRegistry.find("wikiagent.model.cost.total")
                .tag("model", "qwen-plus").counters().stream()
                .mapToDouble(c -> c.count()).sum();

        // 成功调用（有 token、有估价）
        recorder.record(ModelCallLogPurpose.CHAT, "obs-it", "qwen-plus",
                100, 50, 12L, true, null, "u1", "s1");
        // 失败调用：只计 calls，不计 cost/tokens
        recorder.record(ModelCallLogPurpose.CHAT, "obs-it", "qwen-plus",
                null, null, null, false, null, "u1", "s1");

        assertThat(meterRegistry.find("wikiagent.model.calls.total")
                .tag("provider", "obs-it").tag("status", "ok").counter().count())
                .isEqualTo(before == 0 ? 1 : before + 1);
        assertThat(meterRegistry.find("wikiagent.model.calls.total")
                .tag("provider", "obs-it").tag("status", "failed").counter().count())
                .isPositive();
        assertThat(meterRegistry.find("wikiagent.model.tokens.total")
                .tag("direction", "in").tag("model", "qwen-plus").counter().count())
                .isGreaterThanOrEqualTo(100);
        assertThat(meterRegistry.find("wikiagent.model.tokens.total")
                .tag("direction", "out").tag("model", "qwen-plus").counter().count())
                .isGreaterThanOrEqualTo(50);
        assertThat(meterRegistry.find("wikiagent.model.cost.total")
                .tag("model", "qwen-plus").counter().count())
                .isGreaterThan(costBefore);
    }

    @Test
    void 任务完成后duration指标出现且backlog可刷新() throws Exception {
        String bizKey = "obs-metrics-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("taskType", "OBS_METRICS", "bizKey", bizKey))))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = JSON.readTree(result.getResponse().getContentAsString()).get("taskId").asText();
        await(() -> taskRepo.findByTaskId(taskId).map(TaskInstance::status)
                .filter(s -> s == TaskStatus.COMPLETED).isPresent(), 15_000);
        // 状态落库先于 Timer 记录（TaskWorker 在 runTask 返回后的完成点才 stop 计时样本），
        // 断言前等待计时器出现，消除测试线程与工作线程的读写竞态
        await(() -> meterRegistry.find("wikiagent.task.duration.seconds")
                .tag("taskType", "OBS_METRICS").tag("result", "completed").timer() != null, 15_000);

        assertThat(meterRegistry.find("wikiagent.task.duration.seconds")
                .tag("taskType", "OBS_METRICS").tag("result", "completed").timer().count())
                .isPositive();

        String body = scrapePrometheus();
        assertThat(body).contains("wikiagent_task_duration_seconds_count");
        assertThat(body).contains("wikiagent_task_duration_seconds_sum");
    }

    private String scrapePrometheus() throws Exception {
        return mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待条件超时");
    }
}
