package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.tool.Toolset;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record Skill(
        String name,
        String description,
        Set<Toolset> requiresToolsets,
        Set<String> platforms,
        String content,
        Path skillDir,
        Map<String, List<String>> linkedFiles
) {
    public String summary() {
        return "- **" + name + "**: " + description;
    }

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

    public boolean requiresToolsetsSatisfied(Set<Toolset> availableToolsets) {
        if (requiresToolsets == null || requiresToolsets.isEmpty()) return true;
        if (availableToolsets == null) return false;
        return availableToolsets.containsAll(requiresToolsets);
    }

    public boolean isActivatable(Set<Toolset> availableToolsets) {
        return matchesPlatform() && requiresToolsetsSatisfied(availableToolsets);
    }

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

    public static Skill ofSimple(String name, String description,
                                  Set<Toolset> requiresToolsets, Set<String> platforms,
                                  String content) {
        return new Skill(name, description, requiresToolsets, platforms, content, null, Map.of());
    }

    public static Skill ofDirectory(String name, String description,
                                     Set<Toolset> requiresToolsets, Set<String> platforms,
                                     String content, Path skillDir,
                                     Map<String, List<String>> linkedFiles) {
        return new Skill(name, description, requiresToolsets, platforms, content, skillDir, linkedFiles);
    }
}