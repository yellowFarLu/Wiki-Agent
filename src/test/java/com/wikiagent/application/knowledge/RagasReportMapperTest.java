package com.wikiagent.application.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.infrastructure.persistence.RagasEvalRunEntity;
import com.wikiagent.infrastructure.persistence.RagasEvalSampleEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAGAS 报告 JSON → JPA 实体映射的纯单测（无 Spring）。
 * 报告样例严格对齐 eval/ragas/run_ragas_eval.py 的输出结构；
 * null 指标必须映射为 null（看板显示"-"），禁止映射成 0。
 */
class RagasReportMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RagasReportMapper mapper = new RagasReportMapper(objectMapper);

    private static final String REPORT = """
            {
              "status": "OK",
              "reason": null,
              "sampleCount": 2,
              "durationSec": 3.4,
              "tool": {
                "ragas": "0.4.3",
                "judgeModel": "qwen-plus",
                "embeddingModel": "text-embedding-v3",
                "endpoint": "https://dashscope.aliyuncs.com/compatible-mode/v1"
              },
              "metrics": {
                "faithfulness": {"value": 1.0, "scored": 2, "missing": 0},
                "answer_relevancy": {"value": 0.9, "scored": 2, "missing": 0},
                "context_precision_without_reference": {"value": 0.75, "scored": 2, "missing": 0},
                "context_recall": {"value": null, "scored": 0, "missing": 2},
                "factual_correctness": {"value": 0.5, "scored": 1, "missing": 1},
                "semantic_similarity": {"value": 0.8, "scored": 2, "missing": 0}
              },
              "samples": [
                {
                  "id": "s1", "domain": "pms",
                  "question": "物业缴费账单在哪里开具发票",
                  "contexts": ["业主完成物业缴费账单后，可在线申请开具发票。"],
                  "answer": "可在线申请开具发票。",
                  "reference": "完成缴费后在线申请开票。",
                  "scores": {
                    "faithfulness": 1.0, "answer_relevancy": 0.9,
                    "context_precision_without_reference": 0.8, "context_recall": null,
                    "factual_correctness": 0.5, "semantic_similarity": 0.82
                  },
                  "errors": {"context_recall": "RuntimeError: boom"}
                },
                {
                  "id": "s2", "domain": "customs",
                  "question": "报关单商品归类申报要素怎么填",
                  "contexts": ["申报报关单时应逐项填写商品归类申报要素。"],
                  "answer": "应逐项填写。",
                  "reference": "逐项填写申报要素。",
                  "scores": {
                    "faithfulness": 1.0, "answer_relevancy": 0.9,
                    "context_precision_without_reference": 0.7, "context_recall": null,
                    "factual_correctness": null, "semantic_similarity": 0.78
                  },
                  "errors": {"context_recall": "RuntimeError: boom", "factual_correctness": "ValueError: x"}
                }
              ]
            }
            """;

    @Test
    void 映射run实体含模型状态与六项聚合指标() throws Exception {
        JsonNode report = objectMapper.readTree(REPORT);

        RagasEvalRunEntity run = mapper.toRun("run-20261005-001", report);

        assertThat(run.getRunId()).isEqualTo("run-20261005-001");
        assertThat(run.getStatus()).isEqualTo("OK");
        assertThat(run.getJudgeModel()).isEqualTo("qwen-plus");
        assertThat(run.getEmbeddingModel()).isEqualTo("text-embedding-v3");
        assertThat(run.getEndpoint()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(run.getSampleCount()).isEqualTo(2);
        assertThat(run.getDurationSec()).isEqualTo(3.4);
        assertThat(run.getFaithfulness()).isEqualTo(1.0);
        assertThat(run.getAnswerRelevancy()).isEqualTo(0.9);
        assertThat(run.getContextPrecision()).isEqualTo(0.75);
        assertThat(run.getContextRecall()).isNull();
        assertThat(run.getFactualCorrectness()).isEqualTo(0.5);
        assertThat(run.getSemanticSimilarity()).isEqualTo(0.8);
    }

    @Test
    void 映射sample实体保留完整用例内容且null指标如实为null() throws Exception {
        JsonNode report = objectMapper.readTree(REPORT);

        List<RagasEvalSampleEntity> samples = mapper.toSamples("run-20261005-001", report);

        assertThat(samples).hasSize(2);
        RagasEvalSampleEntity s1 = samples.get(0);
        assertThat(s1.getRunId()).isEqualTo("run-20261005-001");
        assertThat(s1.getSampleId()).isEqualTo("s1");
        assertThat(s1.getDomainTag()).isEqualTo("pms");
        assertThat(s1.getQuestion()).isEqualTo("物业缴费账单在哪里开具发票");
        assertThat(s1.getContexts()).contains("可在线申请开具发票");
        assertThat(s1.getAnswer()).isEqualTo("可在线申请开具发票。");
        assertThat(s1.getReference()).isEqualTo("完成缴费后在线申请开票。");
        assertThat(s1.getFaithfulness()).isEqualTo(1.0);
        assertThat(s1.getContextRecall()).isNull();
        assertThat(s1.getErrors()).contains("RuntimeError: boom");

        RagasEvalSampleEntity s2 = samples.get(1);
        assertThat(s2.getFactualCorrectness()).isNull();
        assertThat(s2.getSemanticSimilarity()).isEqualTo(0.78);
    }
}
