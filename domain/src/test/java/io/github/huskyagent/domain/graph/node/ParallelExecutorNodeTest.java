package io.github.huskyagent.domain.graph.node;

import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelExecutorNodeTest {

    private final ExecutorService testExecutor = Executors.newFixedThreadPool(2);

    @Test
    void resolvesDynamicToolTimeout() throws Exception {
        ToolDefinition tool = ToolDefinition.of("slow_tool", "Slow", Toolset.CORE, (com.fasterxml.jackson.databind.JsonNode) null, args -> null)
            .withTimeout(args -> Duration.ofSeconds(((Number) args.get("timeout")).longValue()));
        ParallelExecutorNode node = new ParallelExecutorNode(new ParallelExecutorNode.Dependencies(
                null, Set.of(), Set.of(), Map.of("slow_tool", tool), 3, 30, null, testExecutor));
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "slow_tool", "{\"timeout\":5}");

        Duration timeout = invokeResolveToolTimeout(node, call, Duration.ofSeconds(30));

        assertEquals(Duration.ofSeconds(5), timeout);
    }

    @Test
    void timeoutResponseUsesToolSpecificDuration() throws Exception {
        ParallelExecutorNode node = new ParallelExecutorNode(new ParallelExecutorNode.Dependencies(
                null, Set.of(), Set.of(), Map.of(), 3, 30, null, testExecutor));
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "slow_tool", "{}");

        ToolResponseMessage.ToolResponse response = invokeTimeoutResponse(node, call, Duration.ofSeconds(7));

        assertEquals("call-1", response.id());
        assertEquals("slow_tool", response.name());
        assertTrue(response.responseData().contains("timed out after 7 seconds"));
    }

    @SuppressWarnings("unchecked")
    private Duration invokeResolveToolTimeout(ParallelExecutorNode node, AssistantMessage.ToolCall call, Duration defaultTimeout) throws Exception {
        Method method = ParallelExecutorNode.class.getDeclaredMethod("resolveToolTimeout", AssistantMessage.ToolCall.class, Duration.class);
        method.setAccessible(true);
        return (Duration) method.invoke(node, call, defaultTimeout);
    }

    private ToolResponseMessage.ToolResponse invokeTimeoutResponse(ParallelExecutorNode node, AssistantMessage.ToolCall call, Duration timeout) throws Exception {
        Method method = ParallelExecutorNode.class.getDeclaredMethod("timeoutResponse", AssistantMessage.ToolCall.class, Duration.class);
        method.setAccessible(true);
        return (ToolResponseMessage.ToolResponse) method.invoke(node, call, timeout);
    }
}
