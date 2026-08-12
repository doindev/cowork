package dev.cowork.project;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-side directory listing so the UI can offer a "browse for workspace folder"
 * dialog (the browser sandbox cannot produce absolute paths itself). Lists
 * directories only; this is a local, single-user tool.
 */
@RestController
@RequestMapping("/api/fs")
public class FsController {

    public record DirEntry(String name, String path) {
    }

    /** path/parent are null for the roots listing. */
    public record DirListing(String path, String parent, List<DirEntry> dirs) {
    }

    @GetMapping("/dirs")
    public DirListing dirs(@RequestParam(required = false) String path) {
        if (path == null || path.isBlank()) {
            return roots();
        }
        Path dir;
        try {
            dir = Path.of(path.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + dir);
        }
        List<DirEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                try {
                    if (Files.isDirectory(child) && !Files.isHidden(child)
                            && !child.getFileName().toString().startsWith(".")) {
                        entries.add(new DirEntry(child.getFileName().toString(), child.toString()));
                    }
                } catch (IOException ignored) {
                    // Unreadable entry — skip it.
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            throw new IllegalArgumentException("Cannot read directory: " + dir);
        }
        entries.sort(Comparator.comparing(DirEntry::name, String.CASE_INSENSITIVE_ORDER));
        Path parent = dir.getParent();
        return new DirListing(dir.toString(), parent == null ? null : parent.toString(), entries);
    }

    private DirListing roots() {
        List<DirEntry> entries = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null && Files.isDirectory(Path.of(home))) {
            entries.add(new DirEntry("Home (" + home + ")", home));
        }
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            if (Files.isDirectory(root)) {
                entries.add(new DirEntry(root.toString(), root.toString()));
            }
        }
        return new DirListing(null, null, entries);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }
}
