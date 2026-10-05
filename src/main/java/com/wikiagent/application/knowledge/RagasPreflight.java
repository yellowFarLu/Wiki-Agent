package com.wikiagent.application.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * RAGAS 评测前置检查：Python 可执行 → 可 import ragas → 脚本存在可读 → API key 非空。
 * 全部通过返回 empty；否则返回诚实中文原因（控制器据此返回 409，不伪造评测）。
 */
public class RagasPreflight {

    /** @param python Python 解释器路径（或 PATH 中的命令）；@param scriptPath 评测脚本路径。 */
    public record Config(String python, Path scriptPath) {}

    private final Config config;

    public RagasPreflight(Config config) {
        this.config = config;
    }

    public Optional<String> check(String dashscopeApiKey) {
        RagasProcesses.Result version = RagasProcesses.run(
                List.of(config.python(), "--version"), 20);
        if (version == null) {
            return Optional.of("未找到 Python 可执行文件: " + config.python()
                    + "（可通过 wikiagent.ragas.python 配置）");
        }
        if (version.exitCode() != 0) {
            return Optional.of("Python 无法运行: " + version.output().strip());
        }

        if (!Files.isRegularFile(config.scriptPath()) || !Files.isReadable(config.scriptPath())) {
            return Optional.of("RAGAS 评测脚本不存在或不可读: " + config.scriptPath()
                    + "（可通过 wikiagent.ragas.script-path 配置）");
        }

        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            return Optional.of("未配置 DASHSCOPE_API_KEY，无法执行 RAGAS 评测");
        }

        // 昂贵检查放最后：确认解释器内已装 ragas
        RagasProcesses.Result importRagas = RagasProcesses.run(
                List.of(config.python(), "-c", "import ragas"), 30);
        if (importRagas == null || importRagas.exitCode() != 0) {
            return Optional.of("Python 环境缺少 ragas，请执行 pip install -r eval/ragas/requirements.txt"
                    + "（当前解释器: " + config.python() + "）");
        }
        return Optional.empty();
    }
}
