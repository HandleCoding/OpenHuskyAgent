package io.github.huskyagent.domain.prompt;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 上下文文件加载器
 *
 * 加载项目中的上下文文件，按优先级顺序：
 * 1. .hermes.md / HERMES.md（向上遍历到 git root）
 * 2. AGENTS.md（cwd only）
 * 3. CLAUDE.md（cwd only）
 * 4. .cursorrules / .cursor/rules/*.mdc
 */
@Component
public class ContextFileLoader {

    /**
     * 上下文文件优先级（第一个匹配生效）
     */
    private static final List<String> CONTEXT_FILE_NAMES = List.of(
        ".hermes.md",
        "HERMES.md",
        "AGENTS.md",
        "CLAUDE.md",
        ".cursorrules"
    );

    /**
     * 已加载的文件记录
     */
    public record LoadedFile(String fileName, String content, Path path) {}

    /**
     * 从工作目录加载上下文文件
     *
     * @param workingDirectory 工作目录
     * @return 加载的文件列表（按优先级顺序，只取第一个匹配）
     */
    public List<LoadedFile> loadContextFiles(Path workingDirectory) {
        List<LoadedFile> result = new ArrayList<>();

        if (workingDirectory == null || !Files.isDirectory(workingDirectory)) {
            return result;
        }

        // 1. 向上遍历查找 .hermes.md / HERMES.md
        Path hermesFile = findFileUpwards(workingDirectory, ".hermes.md");
        if (hermesFile == null) {
            hermesFile = findFileUpwards(workingDirectory, "HERMES.md");
        }
        if (hermesFile != null) {
            addFileIfExists(result, hermesFile);
        }

        // 2. 在当前目录查找其他文件
        for (String fileName : CONTEXT_FILE_NAMES) {
            if (fileName.startsWith(".")) continue;  // 已经在向上遍历中处理

            Path file = workingDirectory.resolve(fileName);
            addFileIfExists(result, file);
        }

        // 3. 加载 .cursor/rules/*.mdc
        Path cursorRulesDir = workingDirectory.resolve(".cursor/rules");
        if (Files.isDirectory(cursorRulesDir)) {
            try {
                Files.list(cursorRulesDir)
                    .filter(p -> p.toString().endsWith(".mdc"))
                    .forEach(p -> addFileIfExists(result, p));
            } catch (Exception e) {
                // 忽略读取错误
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

    /**
     * 向上遍历查找文件（直到 git root 或 home directory）
     */
    private Path findFileUpwards(Path start, String fileName) {
        Path current = start;

        while (current != null) {
            Path file = current.resolve(fileName);
            if (Files.isRegularFile(file)) {
                return file;
            }

            // 检查是否到达 git root
            if (Files.isDirectory(current.resolve(".git"))) {
                break;
            }

            // 向上一级
            current = current.getParent();

            // 不要超出用户 home 目录
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

    /**
     * 添加文件到结果列表（如果存在且可读）
     */
    private void addFileIfExists(List<LoadedFile> result, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            String content = Files.readString(file);

            // 安全检查：检测 prompt injection
            if (containsInjectionPatterns(content)) {
                // 记录警告但不加载
                return;
            }

            result.add(new LoadedFile(
                file.getFileName().toString(),
                content,
                file
            ));
        } catch (Exception e) {
            // 忽略读取错误
        }
    }

    /**
     * 检测 prompt injection 模式
     */
    private boolean containsInjectionPatterns(String content) {
        if (content == null) return false;

        // 基础安全检查
        String lower = content.toLowerCase();

        // 检测可疑模式
        if (lower.contains("ignore previous instructions")) return true;
        if (lower.contains("ignore all previous")) return true;
        if (lower.contains("forget everything")) return true;
        if (lower.contains("system: you are now")) return true;
        if (lower.contains("<|im_start|>system")) return true;

        return false;
    }
}