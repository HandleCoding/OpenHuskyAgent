package io.github.huskyagent.application.runtime;

import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.scene.SceneConfig;
import io.github.huskyagent.infra.memory.BuiltinMemoryProvider;
import io.github.huskyagent.infra.memory.SessionMemoryProvider;
import io.github.huskyagent.infra.mcp.McpToolNames;
import io.github.huskyagent.infra.skill.Skill;
import io.github.huskyagent.infra.skill.SkillManager;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CapabilityVisibilityResolver {
    private final SkillManager skillManager;

    public CapabilityView resolve(SceneConfig sceneConfig, List<ToolDefinition> candidateTools) {
        List<ToolDefinition> tools = filterTools(sceneConfig, candidateTools != null ? candidateTools : List.of());

        boolean stripApproval = sceneConfig.getApprovalPolicy() == SceneConfig.ApprovalPolicy.NONE;
        if (stripApproval) {
            tools = tools.stream()
                    .map(this::withoutApproval)
                    .toList();
        }

        Set<Toolset> visibleToolsets = tools.stream().map(ToolDefinition::toolset).collect(Collectors.toUnmodifiableSet());
        List<Skill> skills = skillManager.getActiveSkills(visibleToolsets);
        if (sceneConfig.getSkillIds() != null && !sceneConfig.getSkillIds().isEmpty()) {
            skills = skills.stream()
                    .filter(skill -> sceneConfig.getSkillIds().contains(skill.name()))
                    .toList();
        }

        return buildView(
                sceneConfig,
                tools,
                visibleToolsets,
                skills,
                sceneConfig.getPromptSections() != null ? Set.copyOf(sceneConfig.getPromptSections()) : Set.of(),
                stripApproval);
    }

    public CapabilityView resolveSubAgent(SceneConfig sceneConfig, CapabilityView parentView) {
        List<ToolDefinition> tools = parentView != null && parentView.getVisibleTools() != null
                ? parentView.getVisibleTools()
                : List.of();
        tools = filterTools(sceneConfig, tools).stream()
                .filter(tool -> tool.toolset() != Toolset.DELEGATE)
                .map(this::withoutApproval)
                .toList();

        Set<Toolset> visibleToolsets = tools.stream().map(ToolDefinition::toolset).collect(Collectors.toUnmodifiableSet());
        List<Skill> skills = skillManager.getActiveSkills(visibleToolsets);
        Set<String> parentSkillNames = parentView != null && parentView.getVisibleSkillNames() != null
                ? parentView.getVisibleSkillNames()
                : Set.of();
        skills = skills.stream()
                .filter(skill -> parentSkillNames.contains(skill.name()))
                .toList();
        if (sceneConfig.getSkillIds() != null && !sceneConfig.getSkillIds().isEmpty()) {
            skills = skills.stream()
                    .filter(skill -> sceneConfig.getSkillIds().contains(skill.name()))
                    .toList();
        }

        Set<String> promptSections = parentView != null && parentView.getVisiblePromptSections() != null
                ? Set.copyOf(parentView.getVisiblePromptSections())
                : Set.of();

        return buildView(sceneConfig, tools, visibleToolsets, skills, promptSections, true);
    }

    private List<ToolDefinition> filterTools(SceneConfig sceneConfig, List<ToolDefinition> initialTools) {
        Set<Toolset> allowedToolsets = sceneConfig.getAllowedToolsets();
        List<ToolDefinition> tools = initialTools;
        if (allowedToolsets != null && !allowedToolsets.isEmpty()) {
            tools = tools.stream()
                    .filter(tool -> allowedToolsets.contains(tool.toolset()))
                    .toList();
        }
        if (sceneConfig.getAllowedTools() != null && !sceneConfig.getAllowedTools().isEmpty()) {
            tools = tools.stream()
                    .filter(tool -> sceneConfig.getAllowedTools().contains(tool.name()))
                    .toList();
        }
        tools = tools.stream()
                .filter(tool -> isAllowedMcpTool(tool, sceneConfig.getAllowedMcpServers(), sceneConfig.getDeniedMcpServers()))
                .toList();
        if (sceneConfig.getDeniedTools() != null && !sceneConfig.getDeniedTools().isEmpty()) {
            tools = tools.stream()
                    .filter(tool -> !sceneConfig.getDeniedTools().contains(tool.name()))
                    .toList();
        }
        return filterByMemoryPolicy(tools, sceneConfig.getMemoryPolicyConfig());
    }

    private CapabilityView buildView(SceneConfig sceneConfig, List<ToolDefinition> tools, Set<Toolset> visibleToolsets,
                                     List<Skill> skills, Set<String> promptSections, boolean stripApproval) {
        return CapabilityView.builder()
                .sceneId(sceneConfig.getSceneId())
                .visibleTools(List.copyOf(tools))
                .visibleToolNames(tools.stream().map(ToolDefinition::name).collect(Collectors.toUnmodifiableSet()))
                .visibleToolsets(visibleToolsets)
                .visibleSkills(List.copyOf(skills))
                .visibleSkillNames(skills.stream().map(Skill::name).collect(Collectors.toUnmodifiableSet()))
                .visiblePromptSections(promptSections != null ? Set.copyOf(promptSections) : Set.of())
                .stripApproval(stripApproval)
                .build();
    }

    private List<ToolDefinition> filterByMemoryPolicy(List<ToolDefinition> tools, SceneConfig.MemoryPolicySpec memorySpec) {
        if (memorySpec == null) {
            return tools;
        }
        if (!memorySpec.isEnabled() || memorySpec.getAccess() == SceneConfig.MemoryAccess.DISABLED) {
            return tools.stream().filter(t -> t.toolset() != Toolset.MEMORY).toList();
        }
        Set<String> providers = memorySpec.getProviders();
        if (providers == null || providers.isEmpty()) {
            return tools;
        }
        Set<String> toRemove = new HashSet<>();
        if (!providers.contains(BuiltinMemoryProvider.NAME)) {
            toRemove.addAll(Set.of("memory_read", "memory_write", "memory_append", "user_read", "user_write", "user_append"));
        }
        if (!providers.contains(SessionMemoryProvider.NAME)) {
            toRemove.add("session_search");
        }
        return toRemove.isEmpty() ? tools : tools.stream().filter(t -> !toRemove.contains(t.name())).toList();
    }

    private ToolDefinition withoutApproval(ToolDefinition tool) {
        if (!tool.requiresApproval()) {
            return tool;
        }
        return tool.withoutApproval();
    }

    private boolean isAllowedMcpTool(ToolDefinition tool, Set<String> allowedServers, Set<String> deniedServers) {
        if (tool.toolset() != Toolset.MCP) {
            return true;
        }
        String server = McpToolNames.serverName(tool.name());
        if (server == null) {
            return true;
        }
        if (deniedServers != null && deniedServers.contains(server)) {
            return false;
        }
        return allowedServers == null || allowedServers.isEmpty() || allowedServers.contains(server);
    }
}
