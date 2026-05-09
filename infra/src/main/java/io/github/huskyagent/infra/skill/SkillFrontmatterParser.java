package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.tool.Toolset;
import org.yaml.snakeyaml.Yaml;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 SKILL.md 的 YAML frontmatter + Markdown 正文。
 *
 * Frontmatter 格式：
 * ---
 * name: web_research
 * description: 深度网络调研
 * requires_toolsets: [WEB, SEARCH]
 * platforms: [macos, linux]
 * ---
 */
public class SkillFrontmatterParser {

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);

    private final Yaml yaml = new Yaml();

    public Skill parse(String fileName, String rawContent) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(rawContent);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Skill file '%s' missing YAML frontmatter".formatted(fileName));
        }

        String frontmatterYaml = matcher.group(1);
        String markdownContent = rawContent.substring(matcher.end());

        Map<String, Object> data = yaml.load(frontmatterYaml);

        String name = requiredString(data, "name", fileName);
        String description = requiredString(data, "description", fileName);
        Set<Toolset> requiresToolsets = parseToolsets(data.get("requires_toolsets"));
        Set<String> platforms = parsePlatforms(data.get("platforms"));

        return Skill.ofSimple(name, description, requiresToolsets, platforms, markdownContent.trim());
    }

    private String requiredString(Map<String, Object> data, String key, String fileName) {
        Object value = data.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Skill file '%s' missing required field '%s'".formatted(fileName, key));
        }
        return value.toString();
    }

    private Set<Toolset> parseToolsets(Object value) {
        if (value == null) return Set.of();
        if (value instanceof List<?> list) {
            Set<Toolset> result = new HashSet<>();
            for (Object item : list) {
                String toolsetStr = item.toString().toUpperCase();
                try {
                    result.add(Toolset.valueOf(toolsetStr));
                } catch (IllegalArgumentException e) {
                    // Unknown toolset — skip silently
                }
            }
            return result;
        }
        return Set.of();
    }

    private Set<String> parsePlatforms(Object value) {
        if (value == null) return Set.of();
        if (value instanceof List<?> list) {
            Set<String> result = new HashSet<>();
            for (Object item : list) {
                result.add(item.toString().toLowerCase());
            }
            return result;
        }
        return Set.of();
    }
}