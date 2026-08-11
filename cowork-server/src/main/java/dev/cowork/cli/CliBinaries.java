package dev.cowork.cli;

import java.io.File;
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
        String found = findOnPath(name);
        return found != null && (found.endsWith(".cmd") || found.endsWith(".bat"));
    }

    public static boolean onPath(String name) {
        return findOnPath(name) != null;
    }

    private static String findOnPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        List<String> extensions = WINDOWS ? List.of(".exe", ".cmd", ".bat") : List.of("");
        for (String dir : pathEnv.split(File.pathSeparator)) {
            for (String ext : extensions) {
                File candidate = new File(dir, name + ext);
                if (candidate.isFile() && candidate.canExecute()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return null;
    }
}
