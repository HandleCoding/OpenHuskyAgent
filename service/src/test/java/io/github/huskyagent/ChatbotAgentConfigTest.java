package io.github.huskyagent;

import io.github.huskyagent.application.runtime.RuntimePolicyResolver;
import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.domain.agent.AgentResolver;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class ChatbotAgentConfigTest extends AbstractIntegrationTest {

    @Autowired
    private AgentResolver agentResolver;

    @Autowired
    private RuntimePolicyResolver runtimePolicyResolver;

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void chatbotSceneDoesNotExposeTerminalToolsWithoutApproval() {
        AgentDefinition scene = agentResolver.resolve("chatbot");

        var policy = runtimePolicyResolver.resolve(scene, toolRegistry.getAllEnabled());
        var visibleTools = policy.getCapabilityView().getVisibleToolNames();

        assertEquals(AgentDefinition.ApprovalPolicy.NONE, scene.getApprovalPolicy());
        assertFalse(policy.getSystemPrompt().isBlank());
        assertFalse(scene.getAllowedToolsets().contains(Toolset.TERMINAL));
        assertTrue(scene.getDeniedTools().contains("terminal"));
        assertTrue(scene.getDeniedTools().contains("process"));
        assertTrue(scene.getDeniedTools().contains("apply_patch"));
        assertFalse(scene.getDeniedTools().contains("patch"));
        assertFalse(visibleTools.contains("terminal"));
        assertFalse(visibleTools.contains("process"));
        assertFalse(visibleTools.contains("apply_patch"));
    }
}
