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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAGAS 预检拒绝路径 IT：Python 解释器不存在 → POST 409 诚实原因、不产生任何 run 行；
 * GET 历史为空列表（看板显示"暂无 RAGAS 评测记录"，不显示 0）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "wikiagent.ragas.python=/nonexistent/python-xyz-123"
})
class RagasPreflightRejectIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-ragas-reject-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private RagasEvalRunJpaDao runDao;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void python缺失时返回409且看板历史为空() throws Exception {
        MockHttpServletResponse resp = mockMvc.perform(post("/api/metrics/ragas/run"))
                .andExpect(status().isConflict())
                .andReturn().getResponse();
        JsonNode body = objectMapper.readTree(resp.getContentAsString());
        assertThat(body.path("message").asText()).contains("Python");

        assertThat(runDao.findAll()).isEmpty();

        MockHttpServletResponse runsResp = mockMvc.perform(get("/api/metrics/ragas/runs"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(objectMapper.readTree(runsResp.getContentAsString())).isEmpty();
    }
}
