package io.github.huskyagent.domain.graph.node;

import io.github.huskyagent.domain.graph.ReActAgentState;
import io.github.huskyagent.domain.graph.RequestToolContext;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.hook.HookResult;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParallelExecutorNodeTest {

    private final ExecutorService testExecutor = Executors.newFixedThreadPool(2);

    @Test
    void resolvesDynamicToolTimeout() throws Exception {
        ToolDefinition tool = ToolDefinition.of("slow_tool", "Slow", Toolset.CORE, (com.fasterxml.jackson.databind.JsonNode) null, args -> null)
            .withTimeout(args -> Duration.ofSeconds(((Number) args.get("timeout")).longValue()));
        ParallelExecutorNode node = new ParallelExecutorNode(new ParallelExecutorNode.Dependencies(
                Set.of(), 3, 30, null, testExecutor));
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "slow_tool", "{\"timeout\":5}");

        Duration timeout = invokeResolveToolTimeout(node, call, Duration.ofSeconds(30), Map.of("slow_tool", tool));

        assertEquals(Duration.ofSeconds(5), timeout);
    }

    @Test
    void timeoutResponseUsesToolSpecificDuration() throws Exception {
        ParallelExecutorNode node = new ParallelExecutorNode(new ParallelExecutorNode.Dependencies(
                Set.of(), 3, 30, null, testExecutor));
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "slow_tool", "{}");

        ToolResponseMessage.ToolResponse response = invokeTimeoutResponse(node, call, Duration.ofSeconds(7));

        assertEquals("call-1", response.id());
        assertEquals("slow_tool", response.name());
        assertTrue(response.responseData().contains("timed out after 7 seconds"));
    }

    @Test
    void timeoutReleasesParallelToolWorkerThread() throws Exception {
        ToolCallback callback = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("slow")
                        .description("Slow")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "unused";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                try {
                    Thread.sleep(10_000);
                    return "done";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }
        };
        ToolDefinition slow = ToolDefinition.of("slow", "Slow", Toolset.CORE, (com.fasterxml.jackson.databind.JsonNode) null, args -> null)
                .withTimeout(args -> Duration.ofMillis(100));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParallelExecutorNode node = new ParallelExecutorNode(new ParallelExecutorNode.Dependencies(
                Set.of(), 3, 30, allowHooks(), executor));
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "slow", "{}");
        ReActAgentState state = new ReActAgentState(Map.of(ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(call)));
        RunnableConfig config = RunnableConfig.builder()
                .threadId("session-1")
                .putMetadata(RequestToolContext.METADATA_KEY, RequestToolContext.of(List.of(slow), List.of(callback)))
                .build();

        Map<String, Object> update = node.build().apply(state, config).get();

        ToolResponseMessage message = (ToolResponseMessage) update.get("messages");
        assertTrue(message.getResponses().get(0).responseData().contains("timed out"));
        assertEquals("worker-reusable", executor.submit(() -> "worker-reusable").get(1, TimeUnit.SECONDS));
        executor.shutdownNow();
    }

    private HookRegistry allowHooks() {
        HookRegistry registry = mock(HookRegistry.class);
        when(registry.fireBefore(any(), any(), anyMap())).thenReturn(HookResult.allow());
        return registry;
    }

    @SuppressWarnings("unchecked")
    private Duration invokeResolveToolTimeout(ParallelExecutorNode node, AssistantMessage.ToolCall call, Duration defaultTimeout, Map<String, ToolDefinition> tools) throws Exception {
        Method method = ParallelExecutorNode.class.getDeclaredMethod("resolveToolTimeout", AssistantMessage.ToolCall.class, Duration.class, Map.class);
        method.setAccessible(true);
        return (Duration) method.invoke(node, call, defaultTimeout, tools);
    }

    private ToolResponseMessage.ToolResponse invokeTimeoutResponse(ParallelExecutorNode node, AssistantMessage.ToolCall call, Duration timeout) throws Exception {
        Method method = ParallelExecutorNode.class.getDeclaredMethod("timeoutResponse", AssistantMessage.ToolCall.class, Duration.class);
        method.setAccessible(true);
        return (ToolResponseMessage.ToolResponse) method.invoke(node, call, timeout);
    }
}
