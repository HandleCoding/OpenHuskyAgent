package io.github.huskyagent.infra.skill;

import io.github.huskyagent.infra.session.SessionContext;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.approval.ApprovalRequest;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Skill 工具提供者 — 三层渐进式披露 + skill 管理 + SkillHub 集成：
 *
 * 1. skill_list → 名称+摘要
 * 2. skill_view(name) → SKILL.md 全文 + linked_files 概览
 * 3. skill_view(name, file_path) → 加载具体关联文件
 * 4. skill_manage(action, ...) → create / patch / delete
 * 5. skill_search(query) → 搜索 SkillHub 社区 skill
 * 6. skill_install(slug) → 安装 SkillHub skill 到本地
 */
@Component
public class SkillToolProvider implements ToolProvider {

    private final SkillManager skillManager;
    private final SkillLoader skillLoader;
    private final SkillHubClient skillHubClient;
    private final SkillFrontmatterParser frontmatterParser = new SkillFrontmatterParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillToolProvider(SkillManager skillManager, SkillLoader skillLoader, SkillHubClient skillHubClient) {
        this.skillManager = skillManager;
        this.skillLoader = skillLoader;
        this.skillHubClient = skillHubClient;
    }

    @Override
    public List<ToolDefinition> getTools() {
        List<ToolDefinition> tools = new ArrayList<>(List.of(
            ToolDefinition.contextual("skill_list", "List available skills (name + description) when the user asks about skills or the prompt does not include the skill index. Use skill_view(name) to load full content.", Toolset.SKILLS,
                    noArgsSchema(),
                    (args, context) -> {
                        List<Skill> all = visibleSkills(context);
                        if (all.isEmpty()) return ToolResult.success("No skills available.");
                        StringBuilder sb = new StringBuilder("Available skills:\n");
                        for (Skill skill : all) {
                            sb.append(skill.summary()).append("\n");
                        }
                        return ToolResult.success(sb.toString());
                    }),
            ToolDefinition.contextual("skill_view",
                    "Load detailed skill instructions. Without file_path: returns SKILL.md + linked files overview. "
                    + "With file_path (e.g. 'references/api.md'): returns that specific linked file content.",
                    Toolset.SKILLS,
                    viewArgSchema(),
                    (args, context) -> {
                        String name = (String) args.get("name");
                        String filePath = (String) args.get("file_path");

                        if (name == null || name.isBlank()) return ToolResult.failure("Skill name is required.");
                        Skill skill = visibleSkill(name, context);
                        if (skill == null) return ToolResult.failure("Skill not found or not visible in current scene: " + name);

                        if (filePath != null && !filePath.isBlank()) {
                            String content = skillLoader.loadLinkedFile(name, filePath);
                            if (content == null) {
                                StringBuilder sb = new StringBuilder("File not found: " + filePath + "\n\nAvailable files:\n");
                                if (skill.linkedFiles() != null) {
                                    for (Map.Entry<String, List<String>> entry : skill.linkedFiles().entrySet()) {
                                        sb.append(entry.getKey()).append(":\n");
                                        for (String f : entry.getValue()) sb.append("- ").append(f).append("\n");
                                    }
                                }
                                return ToolResult.failure(sb.toString());
                            }
                            return ToolResult.success("# " + filePath + "\n\n" + content);
                        }

                        return ToolResult.success(skill.buildViewOutput());
                    }),
            ToolDefinition.withApprovalContextual("skill_manage",
                    "Manage skills in configured writable directories: " + skillLoader.managedSkillRootsDescription() + ". "
                    + "'create' — Create a new skill with name, description, and content. "
                    + "'patch' — Update an existing skill's content only when it is loaded from a writable directory. "
                    + "'delete' — Delete a skill and its entire directory only when it is loaded from a writable directory.",
                    Toolset.SKILLS,
                    manageArgSchema(),
                    (args, context) -> {
                        String action = (String) args.get("action");
                        if (action == null || action.isBlank()) return ToolResult.failure("Action is required: create, patch, or delete.");

                        String actionLower = action.toLowerCase();
                        if ("create".equals(actionLower)) return handleCreate(args);
                        if ("patch".equals(actionLower)) return handlePatch(args, context);
                        if ("delete".equals(actionLower)) return handleDelete(args, context);
                        return ToolResult.failure("Unknown action: " + action + ". Use create, patch, or delete.");
                    },
                    this::checkSkillManageApproval),
            ToolDefinition.of("skill_search",
                    "Search SkillHub community skill marketplace for skills matching a query. "
                    + "Returns a list of available community skills with name, description, and slug. "
                    + "Do not install results automatically; ask the user before using skill_install(slug).",
                    Toolset.SKILLS,
                    searchArgSchema(),
                    args -> {
                        String query = (String) args.get("query");
                        int limit = args.get("limit") instanceof Number n ? n.intValue() : 10;

                        if (query == null || query.isBlank()) return ToolResult.failure("Search query is required.");

                        SkillHubClient.SkillHubSearchResponse searchResponse = skillHubClient.searchSkills(query, limit);

                        if (!searchResponse.success()) {
                            return ToolResult.failure(searchResponse.error());
                        }

                        if (searchResponse.results().isEmpty()) return ToolResult.success("No skills found for: " + query);

                        StringBuilder sb = new StringBuilder("SkillHub search results for \"" + query + "\":\n\n");
                        for (int i = 0; i < searchResponse.results().size(); i++) {
                            SkillHubClient.SkillHubSearchResult r = searchResponse.results().get(i);
                            sb.append((i + 1) + ". **" + r.name() + "**");
                            if (r.description() != null) sb.append(" — " + r.description());
                            if (r.category() != null) sb.append(" [" + r.category() + "]");
                            sb.append("\n   Slug: " + r.slug() + "\n");
                        }
                        sb.append("\nAsk the user before using skill_install(slug) to install any of these skills.");
                        return ToolResult.success(sb.toString());
                    }),
            ToolDefinition.withApproval("skill_install",
                    "Install a skill from SkillHub community marketplace. "
                    + "Provide the slug (from skill_search results). "
                    + "Downloads the SKILL.md content and saves it to local skills/ directory.",
                    Toolset.SKILLS,
                    installArgSchema(),
                    args -> {
                        String slug = (String) args.get("slug");
                        if (slug == null || slug.isBlank()) return ToolResult.failure("Skill slug is required.");

                        SkillHubClient.SkillHubSkillDetail detail = skillHubClient.getSkillDetail(slug);

                        if (detail == null) return ToolResult.failure("Failed to fetch skill content for: " + slug
                                + ". The skill may not exist or SkillHub API is unavailable.");

                        // 直接将 SkillHub 返回的原始 SKILL.md 写入磁盘，不重新构建 frontmatter
                        // 这样避免 YAML 解析问题（社区 skill 的 frontmatter 格式可能包含特殊字符）
                        String result = skillLoader.installSkillFromRaw(slug, detail.skillMdRaw());

                        if (result == null) return ToolResult.failure("Failed to install skill: " + slug);

                        return ToolResult.success("Skill installed: " + slug + " (from " + detail.repoUrl() + ")\n" + result);
                    },
                    this::checkSkillInstallApproval)
        ));

        return tools;
    }

    private ApprovalRequest checkSkillManageApproval(Map<String, Object> args) {
        String action = (String) args.get("action");
        String name = (String) args.get("name");
        String reason = "Skill change requires user approval: action=" + safeLabel(action)
                + ", name=" + safeLabel(name) + ". This will modify local skill files.";
        return ApprovalRequest.of(UUID.randomUUID().toString(), "skill_manage", args, reason, currentSessionId());
    }

    private ApprovalRequest checkSkillInstallApproval(Map<String, Object> args) {
        String slug = (String) args.get("slug");
        String reason = "Skill installation requires user approval: slug=" + safeLabel(slug)
                + ". This will download community SkillHub content and write it to the local skills directory.";
        return ApprovalRequest.of(UUID.randomUUID().toString(), "skill_install", args, reason, currentSessionId());
    }

    private String currentSessionId() {
        String sessionId = SessionContext.get();
        return sessionId != null ? sessionId : "default";
    }

    private String safeLabel(String value) {
        return value == null || value.isBlank() ? "<blank>" : value;
    }

    private List<Skill> visibleSkills(ToolExecutionContext context) {
        Set<String> visibleSkillNames = visibleSkillNames(context);
        if (visibleSkillNames == null) {
            return skillManager.getAllSkills();
        }
        if (visibleSkillNames.isEmpty()) {
            return List.of();
        }
        return skillManager.getAllSkills().stream()
                .filter(skill -> visibleSkillNames.contains(skill.name()))
                .toList();
    }

    private Skill visibleSkill(String name, ToolExecutionContext context) {
        Set<String> visibleSkillNames = visibleSkillNames(context);
        if (visibleSkillNames != null && !visibleSkillNames.contains(name)) {
            return null;
        }
        return skillManager.getSkill(name);
    }

    private Set<String> visibleSkillNames(ToolExecutionContext context) {
        if (context != null) {
            return context.visibleSkillNames();
        }
        return SessionContext.getScope() != null ? SessionContext.getScope().getVisibleSkillNames() : null;
    }

    // ── skill_manage handlers ──────────────────────────────────────────

    private ToolResult handleCreate(Map<String, Object> args) {
        String name = (String) args.get("name");
        String description = (String) args.get("description");
        String content = (String) args.get("content");

        if (name == null || name.isBlank()) return ToolResult.failure("Skill name is required.");
        if (description == null || description.isBlank()) return ToolResult.failure("Skill description is required.");
        if (content == null || content.isBlank()) return ToolResult.failure("Skill content is required.");

        if (skillManager.getSkill(name) != null) {
            return ToolResult.failure("Skill already exists: " + name + ". Use 'patch' to update it.");
        }

        String result = skillLoader.createSkill(name, description, content);
        if (result == null) return ToolResult.failure("Failed to create skill: " + name);
        return ToolResult.success("Skill created: " + name + "\n" + result);
    }

    private ToolResult handlePatch(Map<String, Object> args, ToolExecutionContext context) {
        String name = (String) args.get("name");
        String content = (String) args.get("content");

        if (name == null || name.isBlank()) return ToolResult.failure("Skill name is required.");
        if (content == null || content.isBlank()) return ToolResult.failure("New content is required.");

        if (visibleSkill(name, context) == null) {
            return ToolResult.failure("Skill not found or not visible in current scene: " + name + ". Use 'create' to make a new one.");
        }

        String result = skillLoader.patchSkill(name, content);
        if (result == null) {
            return ToolResult.failure("Failed to patch skill: " + name
                    + ". Only skills loaded from configured writable directories can be patched: "
                    + skillLoader.managedSkillRootsDescription());
        }
        return ToolResult.success("Skill patched: " + name + "\n" + result);
    }

    private ToolResult handleDelete(Map<String, Object> args, ToolExecutionContext context) {
        String name = (String) args.get("name");
        String reason = (String) args.get("reason");

        if (name == null || name.isBlank()) return ToolResult.failure("Skill name is required.");

        Skill skill = visibleSkill(name, context);
        if (skill == null) return ToolResult.failure("Skill not found or not visible in current scene: " + name);

        String result = skillLoader.deleteSkill(name);
        if (result == null) {
            return ToolResult.failure("Failed to delete skill: " + name
                    + ". Only skills loaded from configured writable directories can be deleted: "
                    + skillLoader.managedSkillRootsDescription());
        }
        return ToolResult.success("Skill deleted: " + name
                + (reason != null ? " (reason: " + reason + ")" : ""));
    }

    // ── Schema helpers ─────────────────────────────────────────────────

    private ObjectNode noArgsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        return schema;
    }

    private ObjectNode viewArgSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("name")
                .put("type", "string")
                .put("description", "Name of the skill to view");
        properties.putObject("file_path")
                .put("type", "string")
                .put("description", "Optional. Specific linked file to load (e.g. 'references/api.md')");
        var required = schema.putArray("required");
        required.add("name");
        return schema;
    }

    private ObjectNode manageArgSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("action")
                .put("type", "string")
                .put("description", "Action: 'create', 'patch', or 'delete'");
        properties.putObject("name")
                .put("type", "string")
                .put("description", "Skill name");
        properties.putObject("description")
                .put("type", "string")
                .put("description", "Skill description (required for 'create')");
        properties.putObject("content")
                .put("type", "string")
                .put("description", "Skill content — Markdown body for SKILL.md (required for 'create' and 'patch')");
        properties.putObject("reason")
                .put("type", "string")
                .put("description", "Reason for deletion (optional, for 'delete')");

        var required = schema.putArray("required");
        required.add("action");
        required.add("name");
        return schema;
    }

    private ObjectNode searchArgSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("query")
                .put("type", "string")
                .put("description", "Search query for community skills (e.g. 'react', 'debugging', 'code review')");
        properties.putObject("limit")
                .put("type", "number")
                .put("description", "Maximum number of results to return (default 10)");

        var required = schema.putArray("required");
        required.add("query");
        return schema;
    }

    private ObjectNode installArgSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("slug")
                .put("type", "string")
                .put("description", "Skill slug from SkillHub (e.g. 'frontend-design'). Use skill_search first to find available slugs.");

        var required = schema.putArray("required");
        required.add("slug");
        return schema;
    }
}