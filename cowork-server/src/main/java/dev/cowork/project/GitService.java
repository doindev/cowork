package dev.cowork.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.cowork.cli.ProcessExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin git wrapper for project workspaces: lazy init, auto-commits after agent turns,
 * log and diff for the review UI and MCP tools.
 */
@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(60);
    private static final String LOG_FORMAT = "%H%x1f%an%x1f%ct%x1f%s";

    public record CommitInfo(String hash, String author, Instant at, String message, String stat) {
    }

    private final ProcessExecutor executor;

    public GitService(ProcessExecutor executor) {
        this.executor = executor;
    }

    /** Ensures the workspace is a git repository (idempotent). */
    public synchronized void ensureRepo(Path workspace) {
        if (Files.isDirectory(workspace.resolve(".git"))) {
            return;
        }
        try {
            run(workspace, "init");
            run(workspace, "config", "user.email", "cowork@local");
            run(workspace, "config", "user.name", "cowork");
            run(workspace, "commit", "--allow-empty", "-m", "cowork: workspace created");
            log.info("Initialized git repository in {}", workspace);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not initialize git in {}: {}", workspace, e.getMessage());
        }
    }

    /** Stages and commits all changes as the given author. Empty if nothing changed. */
    public synchronized Optional<CommitInfo> commitAll(Path workspace, String authorName, String message) {
        try {
            ensureRepo(workspace);
            run(workspace, "add", "-A");
            var status = run(workspace, "status", "--porcelain");
            if (status.stdout().isBlank()) {
                return Optional.empty();
            }
            var commit = run(workspace, "-c", "user.name=" + authorName,
                    "-c", "user.email=" + authorName + "@cowork.local", "commit", "-m", message);
            if (commit.exitCode() != 0) {
                log.warn("git commit failed in {}: {}", workspace, commit.stderr());
                return Optional.empty();
            }
            List<CommitInfo> latest = log(workspace, 1);
            return latest.isEmpty() ? Optional.empty() : Optional.of(latest.getFirst());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("git commit failed in {}: {}", workspace, e.getMessage());
            return Optional.empty();
        }
    }

    public List<CommitInfo> log(Path workspace, int limit) {
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            return List.of();
        }
        try {
            var result = run(workspace, "log", "--pretty=format:" + LOG_FORMAT,
                    "--shortstat", "-n", String.valueOf(limit));
            if (result.exitCode() != 0) {
                return List.of();
            }
            return parseLog(result.stdout());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    public Optional<String> diff(Path workspace, String hash) {
        if (!hash.matches("[0-9a-fA-F]{6,40}")) {
            throw new IllegalArgumentException("Invalid commit hash");
        }
        try {
            var result = run(workspace, "show", "--format=commit %H%nAuthor: %an%nDate: %ad%n%n    %s%n",
                    hash);
            return result.exitCode() == 0 ? Optional.of(result.stdout()) : Optional.empty();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    private static List<CommitInfo> parseLog(String output) {
        List<CommitInfo> commits = new ArrayList<>();
        String pendingStat = null;
        CommitInfo pending = null;
        for (String line : output.split("\r?\n")) {
            if (line.contains("\u001F")) {
                if (pending != null) {
                    commits.add(withStat(pending, pendingStat));
                }
                String[] parts = line.split("\u001F", -1);
                pending = new CommitInfo(parts[0], parts[1],
                        Instant.ofEpochSecond(Long.parseLong(parts[2])), parts[3], null);
                pendingStat = null;
            } else if (!line.isBlank()) {
                pendingStat = line.trim();
            }
        }
        if (pending != null) {
            commits.add(withStat(pending, pendingStat));
        }
        return commits;
    }

    private static CommitInfo withStat(CommitInfo info, String stat) {
        return new CommitInfo(info.hash(), info.author(), info.at(), info.message(), stat);
    }

    private ProcessExecutor.ExecResult run(Path workspace, String... args)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        return executor.run(cmd, workspace, Map.of(), null, GIT_TIMEOUT);
    }
}
