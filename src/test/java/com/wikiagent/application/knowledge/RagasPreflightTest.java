package com.wikiagent.application.knowledge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAGAS 编排预检的单测（真实子进程，无 mock）。
 * 预检链：python 可执行 → 可 import ragas → 脚本存在 → API key 非空，
 * 任一失败必须返回诚实中文原因（控制器据此返回 409）。
 */
class RagasPreflightTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path SCRIPT = PROJECT_ROOT.resolve("eval/ragas/run_ragas_eval.py");
    private static Path emptyVenvPython;

    @BeforeAll
    static void createEmptyVenv() throws Exception {
        Path venvDir = Files.createTempDirectory("ragas-empty-venv");
        Process p = new ProcessBuilder("python3", "-m", "venv", venvDir.toString()).start();
        assertThat(p.waitFor()).isZero();
        emptyVenvPython = venvDir.resolve("bin/python3");
        assertThat(Files.exists(emptyVenvPython)).isTrue();
    }

    @Test
    void python可执行文件不存在时返回诚实原因() {
        RagasPreflight preflight = new RagasPreflight(
                new RagasPreflight.Config("/nonexistent/python-xyz-123", SCRIPT));

        Optional<String> reason = preflight.check("sk-xxx");

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("Python");
    }

    @Test
    void ragas未安装时返回安装指引() {
        RagasPreflight preflight = new RagasPreflight(
                new RagasPreflight.Config(emptyVenvPython.toString(), SCRIPT));

        Optional<String> reason = preflight.check("sk-xxx");

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("ragas");
    }

    @Test
    void 脚本不存在时返回原因() {
        RagasPreflight preflight = new RagasPreflight(
                new RagasPreflight.Config("python3", PROJECT_ROOT.resolve("eval/ragas/missing.py")));

        Optional<String> reason = preflight.check("sk-xxx");

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("脚本");
    }

    @Test
    void apiKey为空时返回原因() {
        RagasPreflight preflight = new RagasPreflight(
                new RagasPreflight.Config("python3", SCRIPT));

        Optional<String> reason = preflight.check("");

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("DASHSCOPE_API_KEY");
    }

    @Test
    void 全部满足时预检通过() {
        // 本机在 /tmp/ragas-venv 安装了 ragas==0.4.3；不存在则跳过（不伪造通过）
        Path ragasPython = Path.of("/tmp/ragas-venv/bin/python3");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(ragasPython),
                "本机 ragas venv 不存在，跳过全通过路径");

        RagasPreflight preflight = new RagasPreflight(
                new RagasPreflight.Config(ragasPython.toString(), SCRIPT));

        assertThat(preflight.check("sk-xxx")).isEmpty();
    }
}
