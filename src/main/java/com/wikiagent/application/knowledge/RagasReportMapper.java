package com.wikiagent.application.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.infrastructure.persistence.RagasEvalRunEntity;
import com.wikiagent.infrastructure.persistence.RagasEvalSampleEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * RAGAS 报告 JSON → JPA 实体映射（报告结构见 eval/ragas/run_ragas_eval.py 头部）。
 * 纯映射无 IO；指标节点为 null/缺失 → 实体字段 null（看板显示"-"），禁止归零。
 * 报告指标键与实体列的差异：context_precision_without_reference → contextPrecision。
 */
public class RagasReportMapper {

    private static final String CONTEXT_PRECISION_KEY = "context_precision_without_reference";

    private final ObjectMapper objectMapper;

    public RagasReportMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 报告 → run 实体（runId 与 startedAt/createdAt 由编排侧赋值，不在报告中）。 */
    public RagasEvalRunEntity toRun(String runId, JsonNode report) {
        RagasEvalRunEntity run = new RagasEvalRunEntity();
        run.setRunId(runId);
        run.setStatus(textOr(report.path("status"), RagasEvalRunEntity.STATUS_ERROR));
        run.setReason(nullableText(report.path("reason")));
        JsonNode tool = report.path("tool");
        run.setJudgeModel(nullableText(tool.path("judgeModel")));
        run.setEmbeddingModel(nullableText(tool.path("embeddingModel")));
        run.setEndpoint(nullableText(tool.path("endpoint")));
        run.setSampleCount(report.path("sampleCount").asInt(0));
        JsonNode duration = report.path("durationSec");
        run.setDurationSec(decimal(duration));
        JsonNode metrics = report.path("metrics");
        run.setFaithfulness(metricValue(metrics, "faithfulness"));
        run.setAnswerRelevancy(metricValue(metrics, "answer_relevancy"));
        run.setContextPrecision(metricValue(metrics, CONTEXT_PRECISION_KEY));
        run.setContextRecall(metricValue(metrics, "context_recall"));
        run.setFactualCorrectness(metricValue(metrics, "factual_correctness"));
        run.setSemanticSimilarity(metricValue(metrics, "semantic_similarity"));
        return run;
    }

    /** 报告 → 用例集实体列表。 */
    public List<RagasEvalSampleEntity> toSamples(String runId, JsonNode report) {
        List<RagasEvalSampleEntity> list = new ArrayList<>();
        for (JsonNode node : report.path("samples")) {
            RagasEvalSampleEntity s = new RagasEvalSampleEntity();
            s.setRunId(runId);
            s.setSampleId(textOr(node.path("id"), "unknown"));
            s.setDomainTag(nullableText(node.path("domain")));
            // provenance 缺失（旧版脚本产出的报告）时默认人工基线 approved，与 Python 侧一致
            s.setSource(textOr(node.path("source"), "seed-manual"));
            s.setDifficulty(textOr(node.path("difficulty"), "simple"));
            s.setReviewStatus(textOr(node.path("reviewStatus"), "approved"));
            s.setAnswerOrigin(textOr(node.path("answerOrigin"), "manual"));
            JsonNode tagsNode = node.path("tags");
            if (tagsNode.isArray() && tagsNode.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode tag : tagsNode) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(tag.asText());
                }
                s.setTags(sb.length() <= 512 ? sb.toString() : sb.substring(0, 512));
            }
            s.setQuestion(textOr(node.path("question"), ""));
            s.setContexts(toJson(node.path("contexts")));
            s.setAnswer(textOr(node.path("answer"), ""));
            s.setReference(textOr(node.path("reference"), ""));
            JsonNode scores = node.path("scores");
            s.setFaithfulness(score(scores, "faithfulness"));
            s.setAnswerRelevancy(score(scores, "answer_relevancy"));
            s.setContextPrecision(score(scores, CONTEXT_PRECISION_KEY));
            s.setContextRecall(score(scores, "context_recall"));
            s.setFactualCorrectness(score(scores, "factual_correctness"));
            s.setSemanticSimilarity(score(scores, "semantic_similarity"));
            JsonNode errors = node.path("errors");
            if (errors.isObject() && errors.size() > 0) {
                s.setErrors(toJson(errors));
            }
            list.add(s);
        }
        return list;
    }

    /** metrics.<key>.value；value 节点 null/缺失 → null。 */
    private static Double metricValue(JsonNode metrics, String key) {
        return decimal(metrics.path(key).path("value"));
    }

    /** 样本 scores.<key>；null/缺失 → null。 */
    private static Double score(JsonNode scores, String key) {
        return decimal(scores.path(key));
    }

    private static Double decimal(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asDouble();
    }

    private static String nullableText(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static String textOr(JsonNode node, String fallback) {
        String v = nullableText(node);
        return v == null ? fallback : v;
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }
}
