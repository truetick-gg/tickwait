package gg.truetick.tickwait.measure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reads kernel files (/proc, /sys) relative to a root directory. Production uses "/";
 * tests point it at a directory of captured files so parsing runs against real data.
 */
public final class ProcFs {
    private final Path root;

    public ProcFs(Path root) {
        this.root = root;
    }

    public static ProcFs system() {
        return new ProcFs(Paths.get("/"));
    }

    private Path resolve(String absolute) {
        String rel = absolute.startsWith("/") ? absolute.substring(1) : absolute;
        return root.resolve(rel);
    }

    public boolean exists(String absolute) {
        return Files.exists(resolve(absolute));
    }

    public String read(String absolute) throws IOException {
        return new String(Files.readAllBytes(resolve(absolute)), StandardCharsets.UTF_8);
    }

    /** Contents, or null when the file is missing or unreadable. */
    public String readOrNull(String absolute) {
        try {
            return read(absolute);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Entry names of a directory, or an empty list when it can't be listed. */
    public java.util.List<String> list(String absolute) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> dir = Files.newDirectoryStream(resolve(absolute))) {
            for (Path p : dir) {
                out.add(p.getFileName().toString());
            }
        } catch (IOException | RuntimeException e) {
            out.clear();
        }
        return out;
    }

    /** Target of a symlink such as /proc/thread-self ("12/task/12"), or null. */
    public String readLinkOrNull(String absolute) {
        try {
            return Files.readSymbolicLink(resolve(absolute)).toString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
