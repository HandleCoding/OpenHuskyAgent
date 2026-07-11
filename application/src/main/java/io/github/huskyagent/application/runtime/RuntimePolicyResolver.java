package io.github.huskyagent.application.runtime;

import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.context.ContextManagementStrategyResolver;
import io.github.huskyagent.domain.context.policy.ContextPolicy;
import io.github.huskyagent.domain.memory.policy.MemoryPolicyConfig;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.context.ContextConfig;
import io.github.huskyagent.infra.knowledge.KnowledgeManager;
import io.github.huskyagent.infra.memory.MemoryManager;
import io.github.huskyagent.infra.memory.MemoryRuntimeStrategyResolver;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class RuntimePolicyResolver {
    private final CapabilityVisibilityResolver capabilityVisibilityResolver;
    private final ContextConfig contextConfig;
    private final ContextManagementStrategyResolver contextManagementStrategyResolver;
    private final MemoryRuntimeStrategyResolver memoryRuntimeStrategyResolver;
    private final MemoryManager memoryManager;
    private final KnowledgeManager knowledgeManager;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String modelName;

    public RuntimePolicy resolve(AgentDefinition agentDefinition, List<ToolDefinition> candidateTools) {
        return assemble(agentDefinition, capabilityVisibilityResolver.resolve(agentDefinition, candidateTools));
    }

    public RuntimePolicy assemble(AgentDefinition agentDefinition, CapabilityView capabilityView) {
        Objects.requireNonNull(capabilityView, "capabilityView is required");
        ContextPolicy contextPolicy = ContextPolicy.from(agentDefinition.getContextPolicy(), contextConfig, modelName);
        contextManagementStrategyResolver.resolve(contextPolicy.getStrategyId());
        MemoryPolicyConfig memoryPolicy = MemoryPolicyConfig.from(agentDefinition.getMemoryPolicyConfig());
        memoryRuntimeStrategyResolver.resolve(memoryPolicy.getStrategyId());
        memoryManager.validateProviderIds(memoryPolicy.getProviders());
        knowledgeManager.validateSourceIds(agentDefinition.getKnowledgeSources());
        return RuntimePolicy.builder()
                .agentId(agentDefinition.getAgentId())
                .capabilityView(capabilityView)
                .contextPolicy(contextPolicy)
                .memoryPolicy(memoryPolicy)
                .approvalPolicy(agentDefinition.getApprovalPolicy())
                .backendPolicy(agentDefinition.getBackendPolicy())
                .workingDirectoryPolicy(agentDefinition.getWorkingDirectoryPolicy())
                .auditSpec(agentDefinition.getAuditSpec())
                .rateLimitSpec(agentDefinition.getRateLimitSpec())
                .knowledgeSources(agentDefinition.getKnowledgeSources())
                .systemPrompt(agentDefinition.getSystemPrompt())
                .promptFiles(agentDefinition.getPromptFiles())
                .promptFilePolicy(agentDefinition.getPromptFilePolicy())
                .backendSpec(agentDefinition.getBackendSpec())
                .storagePolicy(agentDefinition.getStoragePolicy())
                .storageSpec(agentDefinition.getStorageSpec())
                .fixedWorkingDirectory(agentDefinition.getFixedWorkingDirectory())
                .build();
    }
}
