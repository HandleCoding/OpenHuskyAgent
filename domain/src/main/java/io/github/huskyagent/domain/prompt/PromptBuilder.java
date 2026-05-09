package io.github.huskyagent.domain.prompt;

import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.prompt.section.*;
import io.github.huskyagent.infra.knowledge.KnowledgeManager;
import io.github.huskyagent.infra.memory.MemoryManager;
import io.github.huskyagent.infra.mcp.McpServerConnector;
import io.github.huskyagent.infra.mcp.McpToolNames;
import io.github.huskyagent.infra.skill.SkillManager;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.todo.TodoStore;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class PromptBuilder {

    private final List<PromptSection> sections = new ArrayList<>();
    /** Caches non-dynamic sections per session to avoid rebuilding stable prompt parts every turn. */
    private final Map<String, String> cachedSections = new ConcurrentHashMap<>();

    private final ContextFileLoader contextFileLoader;
    private final MemoryManager memoryManager;
    private final KnowledgeManager knowledgeManager;
    private final McpServerConnector mcpConnector;
    private final SkillManager skillManager;
    private final TodoStore todoStore;
    private final String modelName;
    private final String providerName;
    private final Object toolUseEnforcementConfig;
    private final ZoneId timeZone;

    public PromptBuilder(ContextFileLoader contextFileLoader,
                         MemoryManager memoryManager, @Autowired(required = false) KnowledgeManager knowledgeManager,
                         SkillManager skillManager,
                         TodoStore todoStore,
                         @Autowired(required = false) McpServerConnector mcpConnector,
                         @Value("${spring.ai.openai.chat.options.model:}") String modelName,
                         @Value("${spring.ai.openai.base-url:}") String baseUrl,
                         @Value("${agent.tool-use-enforcement.enforcement:auto}") Object toolUseEnforcementConfig) {
        this.contextFileLoader = contextFileLoader;
        this.memoryManager = memoryManager;
        this.knowledgeManager = knowledgeManager;
        this.skillManager = skillManager;
        this.todoStore = todoStore;
        this.mcpConnector = mcpConnector;
        this.modelName = modelName;
        this.providerName = deriveProviderName(baseUrl);
        this.toolUseEnforcementConfig = toolUseEnforcementConfig;
        this.timeZone = ZoneId.systemDefault();
        registerDefaultSections();
    }

    private static String deriveProviderName(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "openai";
        String host = baseUrl.toLowerCase();
        if (host.contains("anthropic")) return "anthropic";
        if (host.contains("openai")) return "openai";
        if (host.contains("deepseek")) return "deepseek";
        if (host.contains("infini-ai") || host.contains("zhipuai")) return "zhipu";
        if (host.contains("google") || host.contains("gemini")) return "google";
        return "custom";
    }

    private void registerDefaultSections() {
        registerSection(new IdentitySection());

        registerSection(new GatewaySection());

        registerSection(new ChannelContextSection());

        registerSection(new MemorySection(memoryManager));

        registerSection(new SkillSection(skillManager));

        registerSection(new KnowledgeSection(knowledgeManager));

        registerSection(new ContextFileSection(contextFileLoader));

        registerSection(new ToolSection());

        registerSection(new ToolUseEnforcementSection(modelName, toolUseEnforcementConfig));

        McpSection mcpSection = new McpSection();
        mcpSection.setDescriptionSupplier(context -> buildMcpToolsDescription(context));
        registerSection(mcpSection);

        registerSection(new TodoSection(todoStore));

        RuntimeSection runtimeSection = new RuntimeSection(timeZone, modelName, providerName);
        registerSection(runtimeSection);

        log.info("Registered {} prompt sections: {}",
            sections.size(),
            sections.stream().map(PromptSection::getName).toList());
    }

    public void registerSection(PromptSection section) {
        sections.add(section);
        sections.sort(Comparator.comparingInt(PromptSection::getPriority));
        log.debug("Registered prompt section: {} (priority={})",
            section.getName(), section.getPriority());
    }

    public void removeSection(String name) {
        sections.removeIf(s -> s.getName().equals(name));
        cachedSections.remove(name);
    }

    @SuppressWarnings("unchecked")
    public <T extends PromptSection> T getSection(String name) {
        return (T) sections.stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    public String build(PromptContext context) {
        return build(context, SectionMode.ALL);
    }

    public String buildSessionStable(PromptContext context) {
        return build(context, SectionMode.SESSION_STABLE);
    }

    public String buildDynamic(PromptContext context) {
        return build(context, SectionMode.DYNAMIC);
    }

    private String build(PromptContext context, SectionMode mode) {
        requireRuntimePolicy(context);
        applySceneSystemPrompt(context);

        StringBuilder sb = new StringBuilder();

        for (PromptSection section : sections) {
            if (!section.isEnabled() || !isVisibleInRuntimePolicy(section, context) || !mode.includes(section)) {
                continue;
            }

            String content = buildSection(section, context);
            if (content != null && !content.isBlank()) {
                sb.append(content);
            }
        }

        return sb.toString();
    }

    private void requireRuntimePolicy(PromptContext context) {
        if (context == null || context.getRuntimePolicy() == null) {
            throw new IllegalArgumentException("PromptContext.runtimePolicy is required");
        }
    }

    private void applySceneSystemPrompt(PromptContext context) {
        String scenePrompt = context.getSceneSystemPrompt();
        if (scenePrompt != null && !scenePrompt.isBlank()
                && context.getGatewaySystemPrompt().isEmpty()) {
            if ("identity".equals(scenePrompt.trim())) {
                return;
            }
            context.gatewaySystemPrompt(renderTemplate(scenePrompt));
        }
    }

    private String buildSection(PromptSection section, PromptContext context) {
        if (section.isDynamic() || context == null || context.getSessionId() == null) {
            return section.build(context);
        }

        String cacheKey = section.getName() + "_" + context.getSessionId();
        return cachedSections.computeIfAbsent(cacheKey, k -> section.build(context));
    }

    private boolean isVisibleInRuntimePolicy(PromptSection section, PromptContext context) {
        if (context.getRuntimePolicy().getCapabilityView().getVisiblePromptSections().isEmpty()) {
            return true;
        }
        return context.getRuntimePolicy().getCapabilityView().getVisiblePromptSections().contains(section.getName());
    }

    private String renderTemplate(String template) {
        ZonedDateTime now = ZonedDateTime.now(timeZone);
        return template
                .replace("{{currentDateTime}}", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")))
                .replace("{{currentDate}}", now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .replace("{{currentTime}}", now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    public void clearCache() {
        cachedSections.clear();
        log.debug("Prompt cache cleared");
    }

    public void clearCache(String sessionId) {
        cachedSections.keySet().removeIf(key -> key.endsWith("_" + sessionId));
    }

    public List<SectionInfo> getSectionInfos() {
        return sections.stream()
            .map(s -> new SectionInfo(s.getName(), s.getPriority(), s.isEnabled(), s.isDynamic()))
            .toList();
    }

    public record SectionInfo(String name, int priority, boolean enabled, boolean dynamic) {}

    private enum SectionMode {
        ALL {
            @Override
            boolean includes(PromptSection section) {
                return true;
            }
        },
        SESSION_STABLE {
            @Override
            boolean includes(PromptSection section) {
                return !section.isDynamic();
            }
        },
        DYNAMIC {
            @Override
            boolean includes(PromptSection section) {
                return section.isDynamic();
            }
        };

        abstract boolean includes(PromptSection section);
    }

    private String buildMcpToolsDescription(PromptContext context) {
        List<ToolDefinition> visibleTools = visibleMcpTools(context);
        if (visibleTools.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        visibleTools.stream()
                .collect(java.util.stream.Collectors.groupingBy(this::mcpServerName, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()))
                .forEach((serverName, tools) -> {
                    sb.append("### Server: ").append(serverName).append("\n");
                    for (ToolDefinition tool : tools) {
                        sb.append("- **").append(tool.name()).append("**: ");
                        sb.append(tool.description() != null ? tool.description() : "No description");
                        sb.append("\n");
                    }
                    sb.append("\n");
                });
        return sb.toString();
    }

    private List<ToolDefinition> visibleMcpTools(PromptContext context) {
        CapabilityView capabilityView = context.getRuntimePolicy().getCapabilityView();
        if (capabilityView == null || capabilityView.getVisibleTools() == null) {
            return List.of();
        }
        return capabilityView.getVisibleTools().stream()
                .filter(tool -> tool.toolset() == Toolset.MCP)
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    private String mcpServerName(ToolDefinition tool) {
        String name = tool.name();
        String serverName = McpToolNames.serverName(name);
        return serverName != null ? serverName : "unknown";
    }
}
