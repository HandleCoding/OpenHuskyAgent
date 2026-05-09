package io.github.huskyagent.application.runtime;

import io.github.huskyagent.domain.capability.CapabilityView;
import io.github.huskyagent.domain.context.ContextManagementStrategyResolver;
import io.github.huskyagent.domain.context.policy.ContextPolicy;
import io.github.huskyagent.domain.memory.policy.MemoryPolicyConfig;
import io.github.huskyagent.domain.runtime.RuntimePolicy;
import io.github.huskyagent.domain.scene.SceneConfig;
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

    public RuntimePolicy resolve(SceneConfig sceneConfig, List<ToolDefinition> candidateTools) {
        return assemble(sceneConfig, capabilityVisibilityResolver.resolve(sceneConfig, candidateTools));
    }

    public RuntimePolicy assemble(SceneConfig sceneConfig, CapabilityView capabilityView) {
        Objects.requireNonNull(capabilityView, "capabilityView is required");
        ContextPolicy contextPolicy = ContextPolicy.from(sceneConfig.getContextPolicy(), contextConfig, modelName);
        contextManagementStrategyResolver.resolve(contextPolicy.getStrategyId());
        MemoryPolicyConfig memoryPolicy = MemoryPolicyConfig.from(sceneConfig.getMemoryPolicyConfig());
        memoryRuntimeStrategyResolver.resolve(memoryPolicy.getStrategyId());
        memoryManager.validateProviderIds(memoryPolicy.getProviders());
        knowledgeManager.validateSourceIds(sceneConfig.getKnowledgeSources());
        return RuntimePolicy.builder()
                .sceneId(sceneConfig.getSceneId())
                .capabilityView(capabilityView)
                .contextPolicy(contextPolicy)
                .memoryPolicy(memoryPolicy)
                .approvalPolicy(sceneConfig.getApprovalPolicy())
                .backendPolicy(sceneConfig.getBackendPolicy())
                .workingDirectoryPolicy(sceneConfig.getWorkingDirectoryPolicy())
                .auditSpec(sceneConfig.getAuditSpec())
                .rateLimitSpec(sceneConfig.getRateLimitSpec())
                .knowledgeSources(sceneConfig.getKnowledgeSources())
                .systemPrompt(sceneConfig.getSystemPrompt())
                .promptFiles(sceneConfig.getPromptFiles())
                .promptFilePolicy(sceneConfig.getPromptFilePolicy())
                .backendSpec(sceneConfig.getBackendSpec())
                .storagePolicy(sceneConfig.getStoragePolicy())
                .storageSpec(sceneConfig.getStorageSpec())
                .fixedWorkingDirectory(sceneConfig.getFixedWorkingDirectory())
                .build();
    }
}
