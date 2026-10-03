package com.wikiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.service.ingest.IngestionService;
import com.wikiagent.service.store.MilvusStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 子项目 G Task 8：上传时录入生效日期 effectiveDate（ISO-8601 yyyy-MM-dd）。
 * 显式传入 → kb_document 落库该日期 + 设置人/设置时间；缺省 → 服务器当前日期；非法格式 → 400。
 * 任务框架路径（wikiagent.task.enabled=true 默认开）经 payload 透传到 IngestTaskHandler 落库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:file:./data/h2/test-effective-date;AUTO_SERVER=TRUE;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class DocumentControllerEffectiveDateTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KbDocumentRepo docRepo;

    @Autowired
    private IngestionService ingestion;

    @MockBean
    private MilvusStoreService milvus;

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private com.wikiagent.application.knowledge.KnowledgeTaggingService taggingService;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> createdDocIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String docId : createdDocIds) {
            try {
                ingestion.delete(docId);
            } catch (Exception ignored) {
            }
        }
        createdDocIds.clear();
    }

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(150);
        }
        org.junit.jupiter.api.Assertions.fail("等待超时: " + what);
    }

    private void mockEmbedding() {
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[8]).toList();
        });
    }

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 上传 txt 走全流水线，返回 docId。 */
    private String upload(String effectiveDate, String userId) throws Exception {
        MockHttpServletRequestBuilder req = multipart("/api/documents")
                .file(new MockMultipartFile("file", "effective-date-sample.txt", "text/plain",
                        "生效日期链路测试文本内容。".repeat(30).getBytes(StandardCharsets.UTF_8)))
                .header("X-User-Id", userId);
        if (effectiveDate != null) {
            req.param("effectiveDate", effectiveDate);
        }
        String body = mvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String docId = json.readTree(body).get("id").asText();
        createdDocIds.add(docId);
        return docId;
    }

    private boolean docReady(String docId) {
        try {
            KbDocument doc = docRepo.findById(docId).orElse(null);
            return doc != null && KbDocument.READY.equals(doc.getStatus());
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void explicitEffectiveDateIsPersisted() throws Exception {
        mockEmbedding();
        String userId = "eff-user-" + uid();
        String docId = upload("2026-01-15", userId);
        await(() -> docReady(docId), "入库任务 COMPLETED");

        KbDocument doc = docRepo.findById(docId).orElseThrow();
        assertThat(doc.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(doc.getEffectiveSetBy()).isEqualTo(userId);
        assertThat(doc.getEffectiveSetAt()).isNotNull();
    }

    @Test
    void missingEffectiveDateDefaultsToToday() throws Exception {
        mockEmbedding();
        String userId = "eff-user-" + uid();
        String docId = upload(null, userId);
        await(() -> docReady(docId), "入库任务 COMPLETED");

        KbDocument doc = docRepo.findById(docId).orElseThrow();
        assertThat(doc.getEffectiveDate()).isEqualTo(LocalDate.now());
        assertThat(doc.getEffectiveSetBy()).isEqualTo(userId);
        assertThat(doc.getEffectiveSetAt()).isNotNull();
    }

    @Test
    void invalidEffectiveDateReturns400() throws Exception {
        mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "bad-date.txt", "text/plain",
                                "x".getBytes(StandardCharsets.UTF_8)))
                        .header("X-User-Id", "eff-user-bad")
                        .param("effectiveDate", "2026/01/15"))
                .andExpect(status().isBadRequest());
    }
}
