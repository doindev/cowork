package dev.cowork.cli;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Locates CLI binaries on the PATH, handling Windows .exe/.cmd shims. */
public final class CliBinaries {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private CliBinaries() {
    }

    /**
     * Resolves a CLI name to something ProcessBuilder can exec directly. On Windows,
     * npm-installed CLIs are .cmd shims that CreateProcess cannot exec, so those are
     * wrapped by the caller via {@link #needsCmdWrapper(String)}.
     */
    public static String resolve(String name) {
        String found = findOnPath(name);
        return found != null ? found : name;
    }

    public static boolean needsCmdWrapper(String name) {
        String found = resolve(name);
        String lower = found == null ? "" : found.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    public static boolean onPath(String name) {
        return findOnPath(name) != null;
    }

    private static String findOnPath(String name) {
        String override = System.getenv(name.toUpperCase(Locale.ROOT) + "_PATH");
        if (isExecutableFile(override)) {
            return Path.of(override).toAbsolutePath().toString();
        }

        List<String> directories = new ArrayList<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    directories.add(dir);
                }
            }
        }
        if (WINDOWS) {
            addIfSet(directories, System.getenv("APPDATA"), "npm");
            addIfSet(directories, System.getenv("LOCALAPPDATA"), "Programs", name);
            addIfSet(directories, System.getenv("LOCALAPPDATA"), "Microsoft", "WindowsApps");
            addCodexDesktopBins(name, directories);
        } else {
            addIfSet(directories, System.getProperty("user.home"), ".local", "bin");
        }

        return findInDirectories(name, directories, WINDOWS);
    }

    static String findInDirectories(String name, List<String> directories, boolean windows) {
        List<String> extensions = windows ? List.of(".exe", ".cmd", ".bat") : List.of("");
        for (String dir : directories) {
            for (String ext : extensions) {
                File candidate = new File(dir, name + ext);
                if (candidate.isFile() && (windows || candidate.canExecute())) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static boolean isExecutableFile(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        File file = new File(value);
        return file.isFile() && (WINDOWS || file.canExecute());
    }

    private static void addIfSet(List<String> directories, String base, String... parts) {
        if (base == null || base.isBlank()) {
            return;
        }
        Path path = Path.of(base, parts);
        directories.add(path.toString());
    }

    /** The Windows Codex desktop app stores its CLI under bin/&lt;version-hash&gt;/. */
    private static void addCodexDesktopBins(String name, List<String> directories) {
        if (!name.equalsIgnoreCase("codex")) {
            return;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return;
        }
        Path root = Path.of(localAppData, "OpenAI", "Codex", "bin");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(CliBinaries::lastModified).reversed())
                    .map(Path::toString)
                    .forEach(directories::add);
        } catch (Exception ignored) {
            // A locked-down user directory should not prevent normal PATH discovery.
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
