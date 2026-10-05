package com.wikiagent.application.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.infrastructure.persistence.RagasEvalRunEntity;
import com.wikiagent.infrastructure.persistence.RagasEvalRunJpaDao;
import com.wikiagent.infrastructure.persistence.RagasEvalSampleEntity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RAGAS 评测编排器：JVM 侧异步调度 Python 脚本并持久化结果（方案见 docs/operations/eval-baseline.md §6）。
 * <p>
 * 触发链：单飞检查（存在 RUNNING → 409）→ 预检（{@link RagasPreflight}）→
 * RUNNING 行落库 → 后台进程执行 → 读取 RAGAS_REPORT_PATH 报告 → 映射落库。
 * 脚本退出非零但报告存在（如全部指标 401 失败）也如实落 ERROR run + 完整用例集；
 * 超时/无报告 → ERROR run + 原因。应用启动时恢复遗留 RUNNING 行。
 * <p>
 * 主机前置条件：python3 环境已装 requirements（见 eval/ragas/requirements.txt），
 * 可用 wikiagent.ragas.python / wikiagent.ragas.script-path 配置。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.ragas.enabled", havingValue = "true", matchIfMissing = true)
public class RagasEvalExecutor {

    private static final Logger log = LoggerFactory.getLogger(RagasEvalExecutor.class);
    private static final int OUTPUT_TAIL_LIMIT = 8000;

    /** @param code HTTP 风格结果码：202 已受理 / 409 无法执行。 */
    public record StartOutcome(int code, String runId, String message) {}

    private final RagasEvalRunJpaDao runDao;
    private final RagasEvalPersistence persistence;
    private final ObjectMapper objectMapper;
    private final RagasReportMapper mapper;
    private final RagasPreflight preflight;
    private final String configuredPython;
    private final Path configuredScript;
    private final String dashscopeApiKey;
    private final long timeoutSec;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ragas-eval-executor");
        t.setDaemon(true);
        return t;
    });

    public RagasEvalExecutor(RagasEvalRunJpaDao runDao,
                             RagasEvalPersistence persistence,
                             ObjectMapper objectMapper,
                             @Value("${wikiagent.ragas.python:python3}") String python,
                             @Value("${wikiagent.ragas.script-path:eval/ragas/run_ragas_eval.py}") String scriptPath,
                             @Value("${wikiagent.ragas.timeout-sec:900}") long timeoutSec,
                             @Value("${spring.ai.dashscope.api-key:${DASHSCOPE_API_KEY:}}") String dashscopeApiKey) {
        this.runDao = runDao;
        this.persistence = persistence;
        this.objectMapper = objectMapper;
        this.mapper = new RagasReportMapper(objectMapper);
        this.configuredPython = python;
        this.configuredScript = Path.of(scriptPath);
        this.preflight = new RagasPreflight(new RagasPreflight.Config(configuredPython, configuredScript));
        this.timeoutSec = timeoutSec;
        this.dashscopeApiKey = dashscopeApiKey;
    }

    @PostConstruct
    void recover() {
        int recovered = persistence.recoverInterrupted();
        if (recovered > 0) {
            log.info("RAGAS 启动恢复：{} 条遗留 RUNNING 已标记中断", recovered);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 提交一次评测（同步预检 + 单飞，评分异步）；拒绝时不产生任何 run 行。 */
    public synchronized StartOutcome start() {
        Optional<RagasEvalRunEntity> running =
                runDao.findFirstByStatusOrderByCreatedAtDesc(RagasEvalRunEntity.STATUS_RUNNING);
        if (running.isPresent()) {
            return new StartOutcome(409, null, "已有 RAGAS 评测执行中: " + running.get().getRunId());
        }
        Optional<String> preflightReason = preflight.check(dashscopeApiKey);
        if (preflightReason.isPresent()) {
            return new StartOutcome(409, null, preflightReason.get());
        }

        String runId = "ragas-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(java.time.ZonedDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        RagasEvalRunEntity run = new RagasEvalRunEntity();
        run.setRunId(runId);
        run.setStatus(RagasEvalRunEntity.STATUS_RUNNING);
        run.setStartedAt(Instant.now());
        persistence.persistNewRunning(run);

        executor.submit(() -> execute(runId));
        return new StartOutcome(202, runId, "评测已提交，正在后台执行");
    }

    private void execute(String runId) {
        Path reportFile = null;
        try {
            reportFile = Files.createTempFile("ragas-report-", ".json");
            ProcessBuilder pb = new ProcessBuilder(
                    configuredPython, configuredScript.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            pb.environment().put("DASHSCOPE_API_KEY", dashscopeApiKey);
            pb.environment().put("RAGAS_REPORT_PATH", reportFile.toString());

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    output.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // 流读取异常不影响进程结果
                }
            }, "ragas-output-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(2000);
                persistence.failRun(runId, "RAGAS 评测超时（" + timeoutSec + " 秒），已终止",
                        tail(output));
                return;
            }
            reader.join(2000);
            int exitCode = process.exitValue();

            JsonNode report = readReport(reportFile);
            if (report == null) {
                persistence.failRun(runId,
                        "RAGAS 脚本退出码 " + exitCode + "，未产出可读报告", tail(output));
                return;
            }
            RagasEvalRunEntity mappedRun = mapper.toRun(runId, report);
            mappedRun.setOutputLog(tail(output));
            List<RagasEvalSampleEntity> samples = mapper.toSamples(runId, report);
            persistence.persistOutcome(mappedRun, samples);
            log.info("RAGAS 评测完成: runId={} status={} samples={}",
                    runId, mappedRun.getStatus(), samples.size());
        } catch (Exception e) {
            log.warn("RAGAS 评测编排异常: {}", e.getMessage());
            try {
                persistence.failRun(runId, "评测编排异常: " + typeAndMsg(e), null);
            } catch (Exception persistError) {
                log.error("RAGAS 失败状态也无法落库: {}", persistError.getMessage());
            }
        } finally {
            if (reportFile != null) {
                try { Files.deleteIfExists(reportFile); } catch (Exception ignored) { }
            }
        }
    }

    private JsonNode readReport(Path reportFile) {
        try {
            if (!Files.isRegularFile(reportFile) || Files.size(reportFile) == 0) {
                return null;
            }
            return objectMapper.readTree(Files.readString(reportFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("RAGAS 报告解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static String tail(CharSequence output) {
        String s = output.toString();
        return s.length() <= OUTPUT_TAIL_LIMIT ? s : s.substring(s.length() - OUTPUT_TAIL_LIMIT);
    }

    private static String typeAndMsg(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
