package io.github.huskyagent.domain.prompt;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
public class ContextFileLoader {

    /** Ordered by precedence so closer repo-specific guidance wins before generic fallbacks are loaded. */
    private static final List<String> CONTEXT_FILE_NAMES = List.of(
        ".hermes.md",
        "HERMES.md",
        "AGENTS.md",
        "CLAUDE.md",
        ".cursorrules"
    );

    public record LoadedFile(String fileName, String content, Path path) {}

    public List<LoadedFile> loadContextFiles(Path workingDirectory) {
        List<LoadedFile> result = new ArrayList<>();

        if (workingDirectory == null || !Files.isDirectory(workingDirectory)) {
            return result;
        }

        Path hermesFile = findFileUpwards(workingDirectory, ".hermes.md");
        if (hermesFile == null) {
            hermesFile = findFileUpwards(workingDirectory, "HERMES.md");
        }
        if (hermesFile != null) {
            addFileIfExists(result, hermesFile);
        }

        for (String fileName : CONTEXT_FILE_NAMES) {
            if (fileName.startsWith(".")) continue;

            Path file = workingDirectory.resolve(fileName);
            addFileIfExists(result, file);
        }

        Path cursorRulesDir = workingDirectory.resolve(".cursor/rules");
        if (Files.isDirectory(cursorRulesDir)) {
            try {
                Files.list(cursorRulesDir)
                    .filter(p -> p.toString().endsWith(".mdc"))
                    .forEach(p -> addFileIfExists(result, p));
            } catch (Exception e) {
            }
        }

        return result;
    }

    public List<LoadedFile> loadExplicitFiles(Path workingDirectory, Collection<String> fileRefs) {
        List<LoadedFile> result = new ArrayList<>();
        if (workingDirectory == null || fileRefs == null || fileRefs.isEmpty()) {
            return result;
        }
        for (String fileRef : fileRefs) {
            if (fileRef == null || fileRef.isBlank()) continue;
            Path path = Path.of(fileRef);
            if (!path.isAbsolute()) {
                path = workingDirectory.resolve(path).normalize();
            }
            addFileIfExists(result, path);
        }
        return result;
    }

    private Path findFileUpwards(Path start, String fileName) {
        Path current = start;

        while (current != null) {
            Path file = current.resolve(fileName);
            if (Files.isRegularFile(file)) {
                return file;
            }

            if (Files.isDirectory(current.resolve(".git"))) {
                break;
            }

            current = current.getParent();

            if (current != null && current.toString().equals(System.getProperty("user.home"))) {
                Path homeFile = current.resolve(fileName);
                if (Files.isRegularFile(homeFile)) {
                    return homeFile;
                }
                break;
            }
        }

        return null;
    }

    private void addFileIfExists(List<LoadedFile> result, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            String content = Files.readString(file);

            // Skip files that try to override the runtime prompt contract.
            if (containsInjectionPatterns(content)) {
                return;
            }

            result.add(new LoadedFile(
                file.getFileName().toString(),
                content,
                file
            ));
        } catch (Exception e) {
        }
    }

    private boolean containsInjectionPatterns(String content) {
        if (content == null) return false;

        String lower = content.toLowerCase();

        if (lower.contains("ignore previous instructions")) return true;
        if (lower.contains("ignore all previous")) return true;
        if (lower.contains("forget everything")) return true;
        if (lower.contains("system: you are now")) return true;
        if (lower.contains("<|im_start|>system")) return true;

        return false;
    }
}