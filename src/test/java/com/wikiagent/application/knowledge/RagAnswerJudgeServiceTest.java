package com.wikiagent.application.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.spi.ChatModelRequest;
import com.wikiagent.domain.llm.spi.ChatModelResponse;
import com.wikiagent.infrastructure.llm.ChatModelProviderChain;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalEntity;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalJpaDao;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 回答评判服务单测：三态 JSON 解析、无来源样本 faithful=null、
 * LLM 空响应/垃圾输出不得误判（PENDING 重试，达上限置 FAILED）、judgeModel 取实际响应模型。
 */
class RagAnswerJudgeServiceTest {

    private RagAnswerEvalJpaDao dao;
    private KbParentChunkRepo parentRepo;
    private KbDocumentRepo docRepo;
    private ChatModel chatModel;
    private ChatModelProviderChain chain;
    private RagAnswerJudgeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dao = mock(RagAnswerEvalJpaDao.class);
        parentRepo = mock(KbParentChunkRepo.class);
        docRepo = mock(KbDocumentRepo.class);
        chatModel = mock(ChatModel.class);
        chain = mock(ChatModelProviderChain.class);

        ObjectProvider<ChatModelProviderChain> chainProvider = mock(ObjectProvider.class);
        when(chainProvider.getIfAvailable()).thenReturn(chain);
        ObjectProvider<ModelCallRecorder> recorderProvider = mock(ObjectProvider.class);
        when(recorderProvider.getIfAvailable()).thenReturn(null);

        service = new RagAnswerJudgeService(dao, parentRepo, docRepo, chatModel,
                chainProvider, recorderProvider, new ObjectMapper(), "qwen-plus",
                50, 3, 6000);
    }

    private RagAnswerEvalEntity sample(String sourceDocIds, int attempts) {
        RagAnswerEvalEntity e = new RagAnswerEvalEntity();
        e.setSessionId("s-1");
        e.setUserId("u-1");
        e.setQuestion("问题");
        e.setAnswer("答案");
        e.setAnswerHash("hash");
        e.setSourceDocIds(sourceDocIds);
        e.setChannel("agent-rag");
        e.setStatus(RagAnswerEvalEntity.STATUS_PENDING);
        e.setAttempts(attempts);
        return e;
    }

    private void stubChain(String content, String model, boolean degraded) {
        when(chain.call(any(ChatModelRequest.class), any(), anyString(), anyString()))
                .thenReturn(new ChatModelProviderChain.ChainResult(
                        new ChatModelResponse(content, model, 10, 5, 20L), null, degraded));
    }

    @Test
    void 三态解析覆盖布尔字符串与垃圾值() {
        assertThat(RagAnswerJudgeService.parseTriBool(true)).isTrue();
        assertThat(RagAnswerJudgeService.parseTriBool(false)).isFalse();
        assertThat(RagAnswerJudgeService.parseTriBool("true")).isTrue();
        assertThat(RagAnswerJudgeService.parseTriBool("FALSE")).isFalse();
        assertThat(RagAnswerJudgeService.parseTriBool(null)).isNull();
        assertThat(RagAnswerJudgeService.parseTriBool("null")).isNull();
        assertThat(RagAnswerJudgeService.parseTriBool("垃圾")).isNull();
    }

    @Test
    void 无来源样本不判忠实度并判相关性() throws Exception {
        stubChain("{\"faithfulness\":null,\"relevance\":true,\"reason\":\"切题但无引用可校验\"}", "qwen-max", false);
        RagAnswerEvalEntity e = sample(null, 0);
        when(dao.findByStatusOrderByCreatedAtAsc(eq(RagAnswerEvalEntity.STATUS_PENDING), any(Pageable.class)))
                .thenReturn(List.of(e));

        RagAnswerJudgeService.JudgeBatchResult r = service.judgePendingBatch();

        assertThat(r.judged()).isEqualTo(1);
        assertThat(e.getStatus()).isEqualTo(RagAnswerEvalEntity.STATUS_JUDGED);
        assertThat(e.getFaithfulness()).isNull();
        assertThat(e.getRelevance()).isEqualTo(1);
        assertThat(e.getJudgeModel()).isEqualTo("qwen-max");
        // prompt 明确提示无引用 → faithfulness 判 null（诚实口径）
        ArgumentCaptor<ChatModelRequest> captor = ArgumentCaptor.forClass(ChatModelRequest.class);
        verify(chain).call(captor.capture(), any(), anyString(), anyString());
        assertThat(captor.getValue().user()).contains("没有引用知识库文档");
    }

    @Test
    void 有来源样本prompt含参考资料且两维回填() throws Exception {
        stubChain("{\"faithfulness\":true,\"relevance\":true,\"reason\":\"均有依据\"}", "qwen-plus", false);
        com.wikiagent.entity.KbParentChunk chunk = new com.wikiagent.entity.KbParentChunk();
        chunk.setId("p1");
        chunk.setDocId("d1");
        chunk.setParentIndex(0);
        chunk.setContent("依据内容");
        when(parentRepo.findByDocIdOrderByParentIndex("d1")).thenReturn(List.of(chunk));
        com.wikiagent.entity.KbDocument doc = new com.wikiagent.entity.KbDocument();
        doc.setId("d1");
        doc.setFilename("手册.pdf");
        when(docRepo.findById("d1")).thenReturn(java.util.Optional.of(doc));
        RagAnswerEvalEntity e = sample("[\"d1\"]", 0);

        boolean ok = service.judgeOne(e);

        assertThat(ok).isTrue();
        assertThat(e.getFaithfulness()).isEqualTo(1);
        assertThat(e.getRelevance()).isEqualTo(1);
        ArgumentCaptor<ChatModelRequest> captor = ArgumentCaptor.forClass(ChatModelRequest.class);
        verify(chain).call(captor.capture(), any(), anyString(), anyString());
        assertThat(captor.getValue().user()).contains("依据内容").contains("手册.pdf");
    }

    @Test
    void 链空响应不误判且保持PENDING重试() {
        stubChain("", "none", true);
        RagAnswerEvalEntity e = sample(null, 0);
        when(dao.findByStatusOrderByCreatedAtAsc(eq(RagAnswerEvalEntity.STATUS_PENDING), any(Pageable.class)))
                .thenReturn(List.of(e));

        RagAnswerJudgeService.JudgeBatchResult r = service.judgePendingBatch();

        assertThat(r.failed()).isEqualTo(1);
        assertThat(e.getStatus()).isEqualTo(RagAnswerEvalEntity.STATUS_PENDING);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getError()).contains("空响应");
        verify(dao).save(e);
    }

    @Test
    void 重试耗尽置FAILED() {
        stubChain("", "none", true);
        RagAnswerEvalEntity e = sample(null, 2);
        when(dao.findByStatusOrderByCreatedAtAsc(eq(RagAnswerEvalEntity.STATUS_PENDING), any(Pageable.class)))
                .thenReturn(List.of(e));

        service.judgePendingBatch();

        assertThat(e.getStatus()).isEqualTo(RagAnswerEvalEntity.STATUS_FAILED);
        assertThat(e.getAttempts()).isEqualTo(3);
    }

    @Test
    void 垃圾JSON不误判走重试() throws Exception {
        stubChain("这不是 JSON 输出", "qwen-plus", false);
        RagAnswerEvalEntity e = sample(null, 0);

        boolean ok = service.judgeOne(e);

        assertThat(ok).isFalse();
        assertThat(e.getStatus()).isEqualTo(RagAnswerEvalEntity.STATUS_PENDING);
        assertThat(e.getFaithfulness()).isNull();
        assertThat(e.getRelevance()).isNull();
    }

    @Test
    void 两维均null视为无效输出不落JUDGED() throws Exception {
        stubChain("{\"faithfulness\":null,\"relevance\":null,\"reason\":\"不知道\"}", "qwen-plus", false);
        RagAnswerEvalEntity e = sample(null, 0);

        boolean ok = service.judgeOne(e);

        assertThat(ok).isFalse();
        assertThat(e.getStatus()).isEqualTo(RagAnswerEvalEntity.STATUS_PENDING);
    }

    @Test
    void 无待评样本时空批次() {
        when(dao.findByStatusOrderByCreatedAtAsc(eq(RagAnswerEvalEntity.STATUS_PENDING), any(Pageable.class)))
                .thenReturn(List.of());

        RagAnswerJudgeService.JudgeBatchResult r = service.judgePendingBatch();

        assertThat(r.total()).isZero();
        verify(dao, never()).save(any(RagAnswerEvalEntity.class));
    }

    @Test
    void 调度入口异常被吞不外抛() {
        when(dao.findByStatusOrderByCreatedAtAsc(anyString(), any(Pageable.class)))
                .thenThrow(new RuntimeException("db down"));

        service.scheduledJudge();

        verify(dao, never()).save(any(RagAnswerEvalEntity.class));
    }

    @Test
    void mapOf三态值三态回填覆盖字符串布尔() throws Exception {
        // JSON 字符串形式 true/false 也能解析（LLM 偶发输出字符串布尔）
        stubChain("{\"faithfulness\":\"false\",\"relevance\":\"true\",\"reason\":\"部分无据\"}", "qwen-plus", false);
        RagAnswerEvalEntity e = sample(null, 0);

        boolean ok = service.judgeOne(e);

        assertThat(ok).isTrue();
        assertThat(e.getFaithfulness()).isEqualTo(0);
        assertThat(e.getRelevance()).isEqualTo(1);
    }
}
