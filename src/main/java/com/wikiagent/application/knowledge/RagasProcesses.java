package com.wikiagent.application.knowledge;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** RAGAS 编排用的子进程执行工具：合并 stderr、限时、超时强杀。 */
final class RagasProcesses {

    record Result(int exitCode, String output) {}

    private RagasProcesses() {}

    /** 进程无法启动返回 null；超时退出码 -1。 */
    static Result run(List<String> command, long timeoutSec) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (Exception e) {
            return null;
        }
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            output = "[" + e.getClass().getSimpleName() + ": " + e.getMessage() + "]";
        }
        try {
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, output + "\n[进程超时]");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, output + "\n[等待被中断]");
        }
        return new Result(process.exitValue(), output);
    }
}
