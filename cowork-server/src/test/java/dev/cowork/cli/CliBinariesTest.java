package dev.cowork.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CliBinariesTest {

    @TempDir
    Path tempDir;

    @Test
    void findsWindowsNpmCommandShim() throws Exception {
        Path shim = Files.createFile(tempDir.resolve("codex.cmd"));

        assertEquals(shim.toAbsolutePath().toString(),
                CliBinaries.findInDirectories("codex", List.of(tempDir.toString()), true));
    }

    @Test
    void returnsNullWhenBinaryIsAbsent() {
        assertNull(CliBinaries.findInDirectories("codex", List.of(tempDir.toString()), true));
    }
}
