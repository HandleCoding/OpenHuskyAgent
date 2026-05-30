package io.github.huskyagent.domain.graph.node;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.huskyagent.domain.graph.ReActAgentState;
import io.github.huskyagent.domain.graph.RequestToolContext;
import io.github.huskyagent.domain.hook.HookRegistry;
import io.github.huskyagent.domain.hook.HookResult;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.spring.ai.tool.SpringAIToolService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecuteToolNodeTest {

    @Test
    void executesQueueHeadToolAndPopsOnlyFirstRequest() throws Exception {
        SpringAIToolService toolService = mock(SpringAIToolService.class);
        AssistantMessage.ToolCall first = toolCall("terminal", "{}");
        AssistantMessage.ToolCall second = toolCall("another", "{}");
        ToolResponseMessage response = GraphUtilsForTest.toolResponse(first.id(), first.name(), "ok");
        when(toolService.executeFunctions(anyList(), anyMap(), eq("messages")))
                .thenReturn(CompletableFuture.completedFuture(new Command(Map.of("messages", response))));
        ExecuteToolNode node = new ExecuteToolNode(new ExecuteToolNode.Dependencies(
                3,
                30,
                allowHooks()));
        ReActAgentState state = new ReActAgentState(Map.of(
                ReActAgentState.APPROVAL_RESULT, "APPROVED",
                ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(first, second)));

        Map<String, Object> update = node.build().apply(state, config(toolService, tool("terminal"), tool("another"))).get();

        assertEquals(List.of(second), update.get(ReActAgentState.TOOL_EXECUTION_REQUESTS));
        assertEquals("", update.get(ReActAgentState.APPROVAL_RESULT));
        assertEquals(false, update.get(ReActAgentState.LAST_TOOL_FAILED));
        verify(toolService).executeFunctions(eq(List.of(first)), anyMap(), eq("messages"));
    }

    @Test
    void missingDefinitionFailsClearly() {
        ExecuteToolNode node = new ExecuteToolNode(new ExecuteToolNode.Dependencies(
                3, 30, allowHooks()));
        ReActAgentState state = new ReActAgentState(Map.of(
                ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(toolCall("missing", "{}"))));

        Exception error = assertThrows(Exception.class, () -> node.build().apply(state, config(mock(SpringAIToolService.class))).get());

        assertTrue(rootMessage(error).contains("could not find tool definition"));
    }

    @Test
    void timeoutUsesQueueHeadToolDefinition() throws Exception {
        SpringAIToolService toolService = mock(SpringAIToolService.class);
        AssistantMessage.ToolCall call = toolCall("slow", "{\"timeout\":1}");
        when(toolService.executeFunctions(anyList(), anyMap(), eq("messages")))
                .thenReturn(new CompletableFuture<>());
        ToolDefinition slow = tool("slow")
                .withTimeout(args -> Duration.ofMillis(((Number) args.get("timeout")).longValue()));
        ExecuteToolNode node = new ExecuteToolNode(new ExecuteToolNode.Dependencies(
                3, 30, allowHooks()));
        ReActAgentState state = new ReActAgentState(Map.of(
                ReActAgentState.TOOL_EXECUTION_REQUESTS, List.of(call)));

        Map<String, Object> update = node.build().apply(state, config(toolService, slow)).get();

        ToolResponseMessage message = (ToolResponseMessage) update.get("messages");
        assertTrue(message.getResponses().get(0).responseData().contains("timed out after 0 seconds"));
        assertEquals(List.of(), update.get(ReActAgentState.TOOL_EXECUTION_REQUESTS));
        assertEquals(true, update.get(ReActAgentState.LAST_TOOL_FAILED));
    }

    private RunnableConfig config(SpringAIToolService toolService, ToolDefinition... tools) {
        List<ToolDefinition> definitions = List.of(tools);
        Map<String, ToolDefinition> definitionMap = definitions.stream()
                .collect(java.util.stream.Collectors.toMap(ToolDefinition::name, definition -> definition));
        return RunnableConfig.builder()
                .threadId("session-1")
                .putMetadata(RequestToolContext.METADATA_KEY, new RequestToolContext(
                        definitions, List.of(), toolService, definitionMap, Set.of(), null, null))
                .build();
    }

    private AssistantMessage.ToolCall toolCall(String name, String args) {
        return new AssistantMessage.ToolCall("call-" + name, "function", name, args);
    }

    private ToolDefinition tool(String name) {
        return ToolDefinition.of(name, name, Toolset.CORE, JsonNodeFactory.instance.objectNode(), args -> null);
    }

    private HookRegistry allowHooks() {
        HookRegistry registry = mock(HookRegistry.class);
        when(registry.fireBefore(any(), any(), anyMap())).thenReturn(HookResult.allow());
        return registry;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static class GraphUtilsForTest {
        static ToolResponseMessage toolResponse(String id, String name, String data) {
            return ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, data)))
                    .build();
        }
    }
}
