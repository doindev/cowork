package dev.cowork.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Conformance harness using fake CLI scripts instead of the real (paid) CLIs. */
@EnabledOnOs(OS.WINDOWS)
class ProcessExecutorTest {

    @TempDir
    Path dir;

    private final ProcessExecutor executor = new ProcessExecutor();

    @Test
    void capturesStdoutAndExitCode() throws Exception {
        Path fake = fakeCli("echo-fake.cmd", "@echo off\r\necho hello-from-fake\r\nexit /b 0\r\n");
        var result = executor.run(List.of("cmd.exe", "/c", fake.toString()), dir, Map.of(), null,
                Duration.ofSeconds(30));
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("hello-from-fake"));
    }

    @Test
    void deliversStdinToProcess() throws Exception {
        // findstr echoes matching stdin lines — proves the prompt reaches the child intact.
        var result = executor.run(List.of("cmd.exe", "/c", "findstr", "prompt"), dir, Map.of(),
                "line one\r\nthe prompt line\r\n", Duration.ofSeconds(30));
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("the prompt line"));
    }

    @Test
    void passesEnvironmentVariables() throws Exception {
        Path fake = fakeCli("env-fake.cmd", "@echo off\r\necho HOME=%COWORK_TEST_HOME%\r\n");
        var result = executor.run(List.of("cmd.exe", "/c", fake.toString()), dir,
                Map.of("COWORK_TEST_HOME", "xyz-123"), null, Duration.ofSeconds(30));
        assertTrue(result.stdout().contains("HOME=xyz-123"));
    }

    @Test
    void timeoutKillsProcessTree() throws Exception {
        Path fake = fakeCli("slow-fake.cmd", "@echo off\r\nping -n 60 127.0.0.1 > nul\r\n");
        long start = System.nanoTime();
        var result = executor.run(List.of("cmd.exe", "/c", fake.toString()), dir, Map.of(), null,
                Duration.ofSeconds(2));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.timedOut());
        assertTrue(elapsedMs < 30_000, "process tree was not killed promptly (took " + elapsedMs + " ms)");
    }

    @Test
    void claudeShapedJsonRoundTrip() throws Exception {
        // Fake claude: emits the JSON result shape the ClaudeCodeRunner parses.
        Path fake = fakeCli("claude-fake.cmd",
                "@echo off\r\necho {\"result\":\"fake reply\",\"session_id\":\"s-1\",\"total_cost_usd\":0.01,\"is_error\":false}\r\n");
        var result = executor.run(List.of("cmd.exe", "/c", fake.toString()), dir, Map.of(), "hi",
                Duration.ofSeconds(30));
        assertTrue(result.stdout().contains("\"session_id\":\"s-1\""));
    }

    private Path fakeCli(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
