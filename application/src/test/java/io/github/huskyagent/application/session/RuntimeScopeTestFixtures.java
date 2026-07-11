package io.github.huskyagent.application.session;

import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.memory.policy.MemoryPolicyConfig;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.Principal;

import java.nio.file.Path;
import java.util.Set;

final class RuntimeScopeTestFixtures {

    private RuntimeScopeTestFixtures() {}

    static RuntimeScope completeScope() {
        return RuntimeScope.builder()
                .sessionId("session-1")
                .principal(principal())
                .channelIdentity(identity())
                .runtimePolicy(runtimePolicy())
                .workingDirectory(Path.of("/tmp/work"))
                .build();
    }

    static Principal principal() {
        return Principal.builder()
                .id("user-1")
                .channelType(ChannelType.TUI)
                .build();
    }

    static ChannelIdentity identity() {
        return ChannelIdentity.builder()
                .channelType(ChannelType.TUI)
                .conversationType(ConversationType.DIRECT)
                .build();
    }

    static AgentDefinition scene() {
        AgentDefinition scene = new AgentDefinition();
        scene.setAgentId("assistant");
        scene.setKnowledgeSources(Set.of("docs"));
        return scene;
    }

    static RuntimePolicy runtimePolicy() {
        MemoryPolicyConfig memoryPolicy = MemoryPolicyConfig.builder()
                .enabled(true)
                .strategyId("custom")
                .access(AgentDefinition.MemoryAccess.READWRITE)
                .scope(AgentDefinition.MemoryScopePolicy.SESSION)
                .promptMode(AgentDefinition.MemoryPromptMode.FULL)
                .providers(Set.of("builtin"))
                .allowCrossSessionSearch(true)
                .build();
        CapabilityView capabilityView = CapabilityView.builder()
                .visibleSkillNames(Set.of("review"))
                .build();
        return RuntimePolicy.builder()
                .agentId("assistant")
                .memoryPolicy(memoryPolicy)
                .capabilityView(capabilityView)
                .knowledgeSources(Set.of("docs"))
                .build();
    }
}
