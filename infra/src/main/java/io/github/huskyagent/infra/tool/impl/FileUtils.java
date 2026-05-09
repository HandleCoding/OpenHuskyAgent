package io.github.huskyagent.infra.tool.impl;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FileUtils {

    private FileUtils() {}

    static final Set<String> SENSITIVE_PREFIXES = Set.of(
        "/etc/", "/boot/", "/usr/lib/systemd/", "/private/etc/", "/private/var/"
    );

    static final Set<String> BINARY_EXTENSIONS = Set.of(
        ".exe", ".dll", ".so", ".dylib", ".bin", ".dat",
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp",
        ".mp3", ".mp4", ".avi", ".mov", ".wav", ".flac",
        ".zip", ".tar", ".gz", ".rar", ".7z", ".pdf",
        ".class", ".jar", ".war"
    );

    static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
        "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
        "/dev/stdin", "/dev/tty", "/dev/console"
    );

    static final Set<String> IGNORED_DIRS = Set.of(
        ".git", "node_modules", "target", ".idea", "__pycache__",
        ".gradle", "build", "dist", ".cache", "venv", ".tox", ".mvn"
    );

    static boolean isSensitivePath(Path path) {
        String resolved = path.toAbsolutePath().toString();
        return SENSITIVE_PREFIXES.stream().anyMatch(resolved::startsWith);
    }

    static boolean isBinaryFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return BINARY_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    public static List<Path> walkFilesList(Path root) throws IOException {
        validateSearchRoot(root);
        Path realRoot = root.toRealPath();
        List<Path> result = new ArrayList<>();
        walkFilesRecursive(root, realRoot, result);
        return result;
    }

    static Stream<Path> walkFiles(Path root) throws IOException {
        return walkFilesList(root).stream();
    }

    static void validateSearchRoot(Path root) throws IOException {
        if (!Files.exists(root)) {
            throw new IOException("Path not found: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("Path is not a directory: " + root);
        }
    }

    static Predicate<Path> globMatcher(Path root, String glob) {
        String effectiveGlob = glob == null || glob.isBlank() ? "*" : glob;
        PathMatcher relativeMatcher = FileSystems.getDefault().getPathMatcher("glob:" + effectiveGlob);
        PathMatcher basenameMatcher = FileSystems.getDefault().getPathMatcher("glob:" + effectiveGlob);
        List<PathMatcher> globstarZeroDirMatchers = globstarZeroDirMatchers(effectiveGlob);
        boolean pathAware = effectiveGlob.contains("/") || effectiveGlob.contains("**");
        return path -> {
            Path relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
            if (relativeMatcher.matches(relative)) {
                return true;
            }
            if (globstarZeroDirMatchers.stream().anyMatch(matcher -> matcher.matches(relative))) {
                return true;
            }
            return !pathAware && basenameMatcher.matches(path.getFileName());
        };
    }

    private static List<PathMatcher> globstarZeroDirMatchers(String glob) {
        List<String> patterns = new ArrayList<>();
        if (glob.startsWith("**/")) {
            patterns.add(glob.substring(3));
        }
        if (glob.contains("/**/")) {
            patterns.add(glob.replace("/**/", "/"));
        }
        return patterns.stream()
                .distinct()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .collect(Collectors.toList());
    }

    static String generateDiff(String oldContent, String newContent, String filename) {
        if (oldContent.equals(newContent)) return "";

        List<String> oldLines = splitLines(oldContent);
        List<String> newLines = splitLines(newContent);
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + filename,
                "b/" + filename,
                oldLines,
                patch,
                3);
        return String.join("\n", unified) + "\n";
    }

    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>(content.lines().toList());
        if (content.endsWith("\n")) lines.add("");
        return lines;
    }

    private static void walkFilesRecursive(Path dir, Path realRoot, List<Path> result) {
        try (var entries = Files.list(dir)) {
            for (Path entry : entries.collect(Collectors.toList())) {
                if (Files.isSymbolicLink(entry)) {
                    addSymlinkedRegularFile(entry, realRoot, result);
                    continue;
                }

                if (Files.isDirectory(entry)) {
                    String dirName = entry.getFileName().toString();
                    if (!IGNORED_DIRS.contains(dirName) && !dirName.startsWith(".")) {
                        walkFilesRecursive(entry, realRoot, result);
                    }
                } else if (Files.isRegularFile(entry)) {
                    result.add(entry);
                }
            }
        } catch (IOException e) {
            // skip unreadable directories
        }
    }

    private static void addSymlinkedRegularFile(Path entry, Path realRoot, List<Path> result) {
        try {
            Path realTarget = entry.toRealPath();
            if (Files.isRegularFile(realTarget) && realTarget.startsWith(realRoot)) {
                result.add(entry);
            }
        } catch (IOException e) {
            // skip broken or unreadable symlinks
        }
    }

    /**
     * 当文件不存在时，建议相似文件。对标 Hermes find_closest_lines。
     * 评分策略：同目录下同名不同扩展名(90)、子串包含(60)、同扩展名(30)。
     */
    static String suggestSimilarFiles(Path targetPath) {
        Path parent = targetPath.getParent();
        String targetName = targetPath.getFileName() != null ? targetPath.getFileName().toString() : "";
        String targetBase = targetName.contains(".") ? targetName.substring(0, targetName.lastIndexOf('.')) : targetName;
        String targetExt = targetName.contains(".") ? targetName.substring(targetName.lastIndexOf('.') + 1) : "";

        if (parent == null || !Files.exists(parent)) return "";

        record ScoredPath(int score, String path) {}

        List<ScoredPath> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(parent, 3)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !isBinaryFile(p))
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";

                    int score = 0;
                    if (base.equals(targetBase) && !ext.equals(targetExt)) score = 90;  // 同名不同扩展名
                    else if (name.contains(targetBase) || targetBase.contains(name)) score = 60;  // 子串包含
                    else if (ext.equals(targetExt) && !targetExt.isEmpty() && base.length() > 2) score = 30;  // 同扩展名

                    if (score > 0) {
                        candidates.add(new ScoredPath(score, parent.relativize(p).toString()));
                    }
                });
        } catch (IOException e) {
            return "";
        }

        return candidates.stream()
                .sorted((a, b) -> Integer.compare(b.score, a.score))
                .limit(5)
                .map(s -> "  - " + s.path)
                .collect(Collectors.joining("\n"));
    }
}
