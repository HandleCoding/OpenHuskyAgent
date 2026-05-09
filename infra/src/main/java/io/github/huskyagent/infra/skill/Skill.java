package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.tool.Toolset;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 过程性知识单元 — "怎么做某件事"的标准化指令。
 *
 * Skill 是一个目录结构：
 *   skill_name/
 *     SKILL.md        ← 入口文件（YAML frontmatter + Markdown 指令）
 *     references/     ← 参考文档
 *     templates/      ← 输出模板
 *     scripts/        ← 辅助脚本
 *     assets/         ← 其他资源
 */
public record Skill(
        String name,
        String description,
        Set<Toolset> requiresToolsets,
        Set<String> platforms,
        String content,
        Path skillDir,
        Map<String, List<String>> linkedFiles
) {
    /** 摘要行，注入系统 prompt */
    public String summary() {
        return "- **" + name + "**: " + description;
    }

    /** 当前平台是否匹配 */
    public boolean matchesPlatform() {
        if (platforms == null || platforms.isEmpty()) return true;
        return platforms.contains(currentPlatform());
    }

    private static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return "macos";
        if (os.contains("win")) return "windows";
        return "linux";
    }

    /** requires_toolsets 是否全部在 availableToolsets 中 */
    public boolean requiresToolsetsSatisfied(Set<Toolset> availableToolsets) {
        if (requiresToolsets == null || requiresToolsets.isEmpty()) return true;
        if (availableToolsets == null) return false;
        return availableToolsets.containsAll(requiresToolsets);
    }

    /** 综合判断是否激活 */
    public boolean isActivatable(Set<Toolset> availableToolsets) {
        return matchesPlatform() && requiresToolsetsSatisfied(availableToolsets);
    }

    /** 构建 skill_view 的输出：SKILL.md 内容 + linked_files 概览 */
    public String buildViewOutput() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill: ").append(name).append("\n\n");
        sb.append(content).append("\n");

        if (linkedFiles != null && !linkedFiles.isEmpty()) {
            sb.append("\n## Linked Files\n\n");
            for (Map.Entry<String, List<String>> entry : linkedFiles.entrySet()) {
                sb.append("**").append(entry.getKey()).append("**:\n");
                for (String file : entry.getValue()) {
                    sb.append("- ").append(file).append("\n");
                }
                sb.append("\n");
            }
            sb.append("Use `skill_view` with `file_path` parameter to load any linked file.\n");
        }
        return sb.toString();
    }

    /** 创建无关联文件的 Skill */
    public static Skill ofSimple(String name, String description,
                                  Set<Toolset> requiresToolsets, Set<String> platforms,
                                  String content) {
        return new Skill(name, description, requiresToolsets, platforms, content, null, Map.of());
    }

    /** 创建带关联文件的 Skill */
    public static Skill ofDirectory(String name, String description,
                                     Set<Toolset> requiresToolsets, Set<String> platforms,
                                     String content, Path skillDir,
                                     Map<String, List<String>> linkedFiles) {
        return new Skill(name, description, requiresToolsets, platforms, content, skillDir, linkedFiles);
    }
}