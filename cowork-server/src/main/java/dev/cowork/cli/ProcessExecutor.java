package dev.cowork.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs CLI subprocesses safely on Windows: argument lists (never concatenated command
 * strings), prompt via stdin, stdout/stderr drained on virtual threads, and a hard
 * timeout that kills the whole process tree.
 */
@Component
public class ProcessExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProcessExecutor.class);

    public record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {
    }

    public ExecResult run(List<String> command, Path workDir, Map<String, String> extraEnv,
                          String stdin, Duration timeout) throws IOException, InterruptedException {
        return run(command, workDir, extraEnv, stdin, timeout, null, null);
    }

    /**
     * Runs a subprocess. When {@code onStdoutLine} is given, stdout is consumed
     * line-by-line and each line is delivered live (in addition to being collected).
     * {@code onStart} receives the process handle for cancellation support.
     */
    public ExecResult run(List<String> command, Path workDir, Map<String, String> extraEnv,
                          String stdin, Duration timeout, Consumer<Process> onStart,
                          Consumer<String> onStdoutLine) throws IOException, InterruptedException {
        log.debug("Spawning: {} (cwd={})", command, workDir);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        if (extraEnv != null) {
            extraEnv.forEach((name, value) -> {
                if (value == null) {
                    builder.environment().remove(name);
                } else {
                    builder.environment().put(name, value);
                }
            });
        }
        Process process = builder.start();
        if (onStart != null) {
            onStart.accept(process);
        }

        CompletableFuture<String> stdout = onStdoutLine == null
                ? readAllAsync(process.getInputStream())
                : readLinesAsync(process.getInputStream(), onStdoutLine);
        CompletableFuture<String> stderr = readAllAsync(process.getErrorStream());

        try (OutputStream out = process.getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // Process may have exited before reading stdin; the exit code will tell the story.
            log.debug("Failed writing stdin to {}: {}", command.getFirst(), e.getMessage());
        }

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            killTree(process);
            return new ExecResult(-1, safeJoin(stdout), safeJoin(stderr), true);
        }
        return new ExecResult(process.exitValue(), safeJoin(stdout), safeJoin(stderr), false);
    }

    private static CompletableFuture<String> readLinesAsync(java.io.InputStream stream,
                                                            Consumer<String> onLine) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            StringBuilder all = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    all.append(line).append('\n');
                    try {
                        onLine.accept(line);
                    } catch (RuntimeException e) {
                        log.debug("stdout line consumer failed: {}", e.getMessage());
                    }
                }
            } catch (IOException ignored) {
                // Stream closed by process exit or kill — return what we have.
            }
            future.complete(all.toString());
        });
        return future;
    }

    private static CompletableFuture<String> readAllAsync(java.io.InputStream stream) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (stream) {
                future.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                future.complete("");
            }
        });
        return future;
    }

    private static String safeJoin(CompletableFuture<String> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
