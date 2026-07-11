package io.github.huskyagent.domain.capability;

import io.github.huskyagent.infra.skill.Skill;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Value
@Builder
public class CapabilityView {
    String agentId;
    List<ToolDefinition> visibleTools;
    Set<String> visibleToolNames;
    Set<Toolset> visibleToolsets;
    List<Skill> visibleSkills;
    Set<String> visibleSkillNames;
    Set<String> visiblePromptSections;
    boolean stripApproval;

    public String fingerprint() {
        return String.join("|",
                agentId != null ? agentId : "",
                namesFingerprint(visibleToolNames),
                toolsetsFingerprint(visibleToolsets),
                namesFingerprint(visibleSkillNames),
                namesFingerprint(visiblePromptSections),
                Boolean.toString(stripApproval));
    }

    private String namesFingerprint(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        return names.stream().sorted().collect(Collectors.joining(","));
    }

    private String toolsetsFingerprint(Set<Toolset> toolsets) {
        if (toolsets == null || toolsets.isEmpty()) {
            return "";
        }
        return toolsets.stream().map(Toolset::getName).sorted().collect(Collectors.joining(","));
    }
}
