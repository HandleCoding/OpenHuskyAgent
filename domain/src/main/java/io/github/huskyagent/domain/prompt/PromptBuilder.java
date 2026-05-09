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

/**
 * Prompt Builder
 *
 * 可扩展的分层系统提示组装器
 *
 * 设计原则：
 * 1. 每个 Section 独立配置，可插拔
 * 2. 按 priority 排序组装
 * 3. 支持动态 Section（每次对话重建）和静态 Section（会话级缓存）
 * 4. 支持未来的 MCP、Skill 等扩展
 *
 * Section 优先级：
 * - 0-99: 核心身份（Identity）
 * - 100-199: 用户/Gateway 提示
 * - 200-299: 持久化内存（Memory）
 * - 300-399: Skills
 * - 400-499: 上下文文件
 * - 500-599: 工具说明
 * - 600-699: MCP/Skill Tools
 * - 900-999: 元信息（DateTime 等）
 */
@Slf4j
@Component
public class PromptBuilder {

    private final List<PromptSection> sections = new ArrayList<>();
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

    /**
     * 注册默认的 Section
     */
    private void registerDefaultSections() {
        // Layer 1: 核心身份 (priority: 10)
        registerSection(new IdentitySection());

        // Layer 2: Gateway 系统提示 (priority: 100)
        registerSection(new GatewaySection());

        // Layer 2.5: 渠道上下文 (priority: 150)
        registerSection(new ChannelContextSection());

        // Layer 3: Memory (priority: 200)
        registerSection(new MemorySection(memoryManager));

        // Layer 4: Skills (priority: 300)
        registerSection(new SkillSection(skillManager));

        // Layer 4.5: Knowledge (priority: 350)
        registerSection(new KnowledgeSection(knowledgeManager));

        // Layer 5: 上下文文件 (priority: 400)
        registerSection(new ContextFileSection(contextFileLoader));

        // Layer 6: 工具说明 (priority: 500)
        registerSection(new ToolSection());

        // Layer 6.5: 工具使用强制指导 (priority: 510，按模型名条件注入)
        registerSection(new ToolUseEnforcementSection(modelName, toolUseEnforcementConfig));

        // Layer 7: MCP (priority: 550，有 MCP 服务器时自动启用)
        McpSection mcpSection = new McpSection();
        mcpSection.setDescriptionSupplier(context -> buildMcpToolsDescription(context));
        registerSection(mcpSection);

        // Layer 7.5: Todo 任务追踪 (priority: 560，动态注入当前任务列表)
        registerSection(new TodoSection(todoStore));

        // Layer 8: 运行时环境 (priority: 800)
        RuntimeSection runtimeSection = new RuntimeSection(timeZone, modelName, providerName);
        registerSection(runtimeSection);

        log.info("Registered {} prompt sections: {}",
            sections.size(),
            sections.stream().map(PromptSection::getName).toList());
    }

    /**
     * 注册新的 Section
     */
    public void registerSection(PromptSection section) {
        sections.add(section);
        // 按 priority 排序
        sections.sort(Comparator.comparingInt(PromptSection::getPriority));
        log.debug("Registered prompt section: {} (priority={})",
            section.getName(), section.getPriority());
    }

    /**
     * 移除 Section
     */
    public void removeSection(String name) {
        sections.removeIf(s -> s.getName().equals(name));
        cachedSections.remove(name);
    }

    /**
     * 获取 Section
     */
    @SuppressWarnings("unchecked")
    public <T extends PromptSection> T getSection(String name) {
        return (T) sections.stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * 构建完整的系统提示
     *
     * @param context 组装上下文
     * @return 完整的系统提示文本
     */
    public String build(PromptContext context) {
        return build(context, SectionMode.ALL);
    }

    /**
     * 构建可随 cached graph 复用的稳定系统提示。
     */
    public String buildSessionStable(PromptContext context) {
        return build(context, SectionMode.SESSION_STABLE);
    }

    /**
     * 构建每次 LLM 调用前都需要刷新的动态系统提示。
     */
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

    /**
     * 清除缓存（当配置变化或压缩事件后）
     */
    public void clearCache() {
        cachedSections.clear();
        log.debug("Prompt cache cleared");
    }

    /**
     * 清除指定会话的缓存
     */
    public void clearCache(String sessionId) {
        cachedSections.keySet().removeIf(key -> key.endsWith("_" + sessionId));
    }

    /**
     * 获取所有已注册的 Section 信息
     */
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

    /**
     * 从当前可见工具视图构建 MCP 工具描述文本，保证 prompt 与 runtime 一致。
     */
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
