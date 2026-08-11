package dev.cowork.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.cowork.cli.ProcessExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip test against a real git repo in a temp dir (git is a hard dependency). */
class GitServiceTest {

    @TempDir
    Path workspace;

    private final GitService git = new GitService(new ProcessExecutor());

    @Test
    void initCommitLogAndDiffRoundTrip() throws Exception {
        git.ensureRepo(workspace);
        assertTrue(Files.isDirectory(workspace.resolve(".git")));

        // No changes yet → no commit beyond the initial empty one.
        assertTrue(git.commitAll(workspace, "architect", "architect: agent turn").isEmpty());

        Files.writeString(workspace.resolve("hello.txt"), "hello world\n");
        var commit = git.commitAll(workspace, "architect", "architect: agent turn").orElseThrow();
        assertEquals("architect", commit.author());
        assertEquals("architect: agent turn", commit.message());
        assertNotNull(commit.stat());
        assertTrue(commit.stat().contains("1 file changed"));

        List<GitService.CommitInfo> log = git.log(workspace, 10);
        assertEquals(2, log.size());
        assertEquals(commit.hash(), log.getFirst().hash());

        String diff = git.diff(workspace, commit.hash()).orElseThrow();
        assertTrue(diff.contains("+hello world"));
        assertTrue(diff.contains("hello.txt"));

        // Short hashes work too.
        assertTrue(git.diff(workspace, commit.hash().substring(0, 8)).isPresent());
    }

    @Test
    void rejectsInvalidHashes() {
        git.ensureRepo(workspace);
        try {
            git.diff(workspace, "; rm -rf /");
            assertFalse(true, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void logOnNonRepoIsEmpty(@TempDir Path plainDir) {
        assertTrue(git.log(plainDir, 5).isEmpty());
    }
}
