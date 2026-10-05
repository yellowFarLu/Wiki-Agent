package com.wikiagent.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAGAS 看板链路集成测试（H2 MODE=MySQL + Flyway，真实 python 子进程，不 mock 中间件）。
 * 默认解释器指向本机 ragas venv（/tmp/ragas-venv，安装 ragas==0.4.3）：
 * venv 缺失时端到端用例如实 skip；拒绝/历史类用例不依赖 venv 始终执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "wikiagent.ragas.python=/tmp/ragas-venv/bin/python3",
        "wikiagent.ragas.timeout-sec=240",
        "spring.ai.dashscope.api-key=sk-fake-it-invalid-key"
})
class RagasDashboardIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-ragas-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private RagasEvalRunJpaDao runDao;
    @Autowired private RagasEvalSampleJpaDao sampleDao;
    @Autowired private ObjectMapper objectMapper;

    @org.junit.jupiter.api.AfterEach
    void cleanRuns() {
        sampleDao.deleteAllInBatch();
        runDao.deleteAllInBatch();
    }

    @Test
    void 已有执行中的评测时返回409() throws Exception {
        RagasEvalRunEntity running = new RagasEvalRunEntity();
        running.setRunId("ragas-running-guard");
        running.setStatus(RagasEvalRunEntity.STATUS_RUNNING);
        running.setStartedAt(Instant.now());
        runDao.saveAndFlush(running);

        mockMvc.perform(post("/api/metrics/ragas/run"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getErrorMessage() == null).isTrue());

        // 确认仍然只有一条 RUNNING 行，没有伪造新 run
        assertThat(runDao.findAll()).hasSize(1);
    }

    @Test
    void 触发评测到脚本失败落库全流程() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.exists(Path.of("/tmp/ragas-venv/bin/python3")),
                "本机 ragas venv 不存在，跳过端到端用例");
        // 假 key：脚本真实执行，72 次调用全部 401 → ERROR run + 12 条用例，指标全 null（不造假）
        MockHttpServletResponse startResp = mockMvc.perform(post("/api/metrics/ragas/run"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse();
        String runId = objectMapper.readTree(startResp.getContentAsString()).path("runId").asText();
        assertThat(runId).startsWith("ragas-");

        JsonNode detail = awaitTerminal(runId);

        assertThat(detail.path("run").path("status").asText()).isEqualTo("ERROR");
        assertThat(detail.path("run").path("sampleCount").asInt()).isEqualTo(12);
        assertThat(detail.path("run").path("faithfulness").isNull()).isTrue();
        assertThat(detail.path("run").path("contextRecall").isNull()).isTrue();
        JsonNode samples = detail.path("samples");
        assertThat(samples).hasSize(12);
        for (JsonNode s : samples) {
            assertThat(s.path("question").asText()).isNotBlank();
            assertThat(objectMapper.readTree(s.path("contexts").asText())).hasSize(1);
            assertThat(s.path("answer").asText()).isNotBlank();
            assertThat(s.path("reference").asText()).isNotBlank();
            assertThat(s.path("faithfulness").isNull()).isTrue();
            assertThat(s.path("errors").asText()).contains("401");
        }
    }

    @Test
    void 历史执行列表最新在前且null指标保持null() throws Exception {
        saveRun("ragas-old", Instant.now().minus(2, ChronoUnit.DAYS),
                RagasEvalRunEntity.STATUS_OK, 0.9, 0.8);
        saveRun("ragas-new", Instant.now().minus(1, ChronoUnit.DAYS),
                RagasEvalRunEntity.STATUS_ERROR, null, null);

        MockHttpServletResponse resp = mockMvc.perform(get("/api/metrics/ragas/runs"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        JsonNode runs = objectMapper.readTree(resp.getContentAsString());

        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).path("runId").asText()).isEqualTo("ragas-new");
        assertThat(runs.get(0).path("faithfulness").isNull()).isTrue();
        assertThat(runs.get(1).path("runId").asText()).isEqualTo("ragas-old");
        assertThat(runs.get(1).path("faithfulness").asDouble()).isEqualTo(0.9);
        assertThat(runs.get(1).path("contextRecall").asDouble()).isEqualTo(0.8);
    }

    private JsonNode awaitTerminal(String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000);
            MockHttpServletResponse resp = mockMvc.perform(get("/api/metrics/ragas/runs/" + runId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse();
            JsonNode detail = objectMapper.readTree(resp.getContentAsString());
            String status = detail.path("run").path("status").asText();
            if (!"RUNNING".equals(status)) {
                return detail;
            }
        }
        throw new AssertionError("等待 RAGAS 评测终态超时: " + runId);
    }

    private void saveRun(String runId, Instant createdAt, String status,
                         Double faithfulness, Double contextRecall) {
        RagasEvalRunEntity r = new RagasEvalRunEntity();
        r.setRunId(runId);
        r.setStatus(status);
        r.setSampleCount(12);
        r.setFaithfulness(faithfulness);
        r.setContextRecall(contextRecall);
        r.setCreatedAt(createdAt);
        r.setStartedAt(createdAt);
        runDao.saveAndFlush(r);
    }
}
