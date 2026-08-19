package dev.cowork.rtk;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtkToolSupportTest {

    @TempDir
    Path dir;

    private RtkToolSupport support() {
        return new RtkToolSupport(dir.resolve("rtk-unwrappable.txt"));
    }

    @Test
    void learnsToolsFromResolveFailures() {
        RtkToolSupport support = support();

        support.learnFrom("rtk: Failed to resolve 'cat' via PATH, falling back to direct exec");

        assertTrue(support.unwrappable().contains("cat"));
        assertTrue(support.promptNote().contains("cat"));
    }

    @Test
    void learnsToolsFromSpawnFailures() {
        RtkToolSupport support = support();

        support.learnFrom("rtk: Failed to run ls: Failed to spawn process: program not found");

        assertTrue(support.unwrappable().contains("ls"));
    }

    @Test
    void learnsEveryToolNamedInOneStream() {
        RtkToolSupport support = support();

        support.learnFrom("""
                rtk: Failed to resolve 'cat' via PATH, falling back to direct exec
                some other output
                rtk: Failed to run grep: Failed to spawn process: program not found
                """);

        assertTrue(support.unwrappable().containsAll(java.util.List.of("cat", "grep")));
    }

    @Test
    void ignoresOutputWithoutFailures() {
        RtkToolSupport support = support();

        support.learnFrom("rtk git status\n* No commits yet on master");

        assertFalse(support.promptNote().contains("git"));
    }

    @Test
    void remembersLearnedToolsAcrossRestarts() throws Exception {
        Path file = dir.resolve("rtk-unwrappable.txt");
        RtkToolSupport first = new RtkToolSupport(file);
        first.learnFrom("rtk: Failed to resolve 'cat' via PATH");
        assertTrue(Files.exists(file));

        assertTrue(new RtkToolSupport(file).unwrappable().contains("cat"));
    }

    @Test
    void recordFailureIsIdempotent() {
        RtkToolSupport support = support();

        assertTrue(support.recordFailure("cat"));
        assertFalse(support.recordFailure("cat"));
        assertFalse(support.recordFailure("  "));
    }
}
