package io.github.huskyagent.infra.tool.todo;

import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TodoToolTest {

    private TodoStore todoStore;
    private TodoTool todoTool;

    @BeforeEach
    void setUp() {
        todoStore = new TodoStore();
        todoTool = new TodoTool(todoStore);
    }

    private Map<String, Object> readArgs() {
        return Map.of(ToolCallbackFactory.SESSION_ID_KEY, "test-session");
    }

    private Map<String, Object> writeArgs(List<Map<String, String>> todos, boolean merge) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put(ToolCallbackFactory.SESSION_ID_KEY, "test-session");
        map.put("todos", todos);
        if (merge) map.put("merge", true);
        return map;
    }

    private Map<String, String> todo(String id, String content, String status) {
        return Map.of("id", id, "content", content, "status", status);
    }

    private String resultText(ToolResult r) {
        return r.success() ? r.content() : r.error();
    }

    @Test
    void writeReplaceAndRead() {
        var todos = List.of(
                todo("1", "Fix the bug", "pending"),
                todo("2", "Write tests", "pending")
        );
        ToolResult writeResult = todoTool.handle(writeArgs(todos, false));
        assertTrue(writeResult.success());
        assertTrue(resultText(writeResult).contains("Fix the bug"));
        assertTrue(resultText(writeResult).contains("Write tests"));

        ToolResult readResult = todoTool.handle(readArgs());
        assertTrue(readResult.success());
        assertTrue(resultText(readResult).contains("Fix the bug"));
    }

    @Test
    void writeReplaceOverwrites() {
        todoTool.handle(writeArgs(List.of(todo("1", "Task A", "pending")), false));
        todoTool.handle(writeArgs(List.of(todo("1", "Task B", "pending")), false));
        assertEquals(1, todoStore.list("test-session").size());
        assertEquals("Task B", todoStore.list("test-session").get(0).content());
    }

    @Test
    void writeMergeUpdatesExisting() {
        todoTool.handle(writeArgs(List.of(
                todo("1", "Task A", "pending"),
                todo("2", "Task B", "pending")
        ), false));

        todoTool.handle(writeArgs(List.of(
                todo("1", "Task A updated", "in_progress")
        ), true));

        var items = todoStore.list("test-session");
        assertEquals(2, items.size());
        assertEquals("Task A updated", items.get(0).content());
        assertEquals(TodoItem.Status.in_progress, items.get(0).status());
        assertEquals("Task B", items.get(1).content());
    }

    @Test
    void writeMergeAddsNew() {
        todoTool.handle(writeArgs(List.of(todo("1", "Task A", "pending")), false));
        todoTool.handle(writeArgs(List.of(todo("2", "Task B", "pending")), true));

        var items = todoStore.list("test-session");
        assertEquals(2, items.size());
    }

    @Test
    void emptyList() {
        ToolResult result = todoTool.handle(readArgs());
        assertTrue(result.success());
        assertTrue(resultText(result).contains("No tasks"));
    }

    @Test
    void summaryCounts() {
        todoTool.handle(writeArgs(List.of(
                todo("1", "Task A", "completed"),
                todo("2", "Task B", "in_progress"),
                todo("3", "Task C", "pending"),
                todo("4", "Task D", "cancelled")
        ), false));

        ToolResult result = todoTool.handle(readArgs());
        assertTrue(resultText(result).contains("1 completed"));
        assertTrue(resultText(result).contains("1 in_progress"));
        assertTrue(resultText(result).contains("1 pending"));
        assertTrue(resultText(result).contains("1 cancelled"));
    }

    @Test
    void todoStoreSessionIsolation() {
        todoStore.replace("session-1", List.of(new TodoItem("1", "Task for session 1", TodoItem.Status.pending)));
        todoStore.replace("session-2", List.of(new TodoItem("1", "Task for session 2", TodoItem.Status.pending)));

        assertEquals(1, todoStore.list("session-1").size());
        assertEquals("Task for session 1", todoStore.list("session-1").get(0).content());
    }

    @Test
    void todoStoreClear() {
        todoStore.replace("session-1", List.of(new TodoItem("1", "Task A", TodoItem.Status.pending)));
        todoStore.clear("session-1");
        assertTrue(todoStore.list("session-1").isEmpty());
    }

    @Test
    void formatForInjection_empty() {
        assertTrue(todoStore.formatForInjection("session-1").isEmpty());
    }

    @Test
    void formatForInjection_onlyActiveItems() {
        todoStore.replace("session-1", List.of(
                new TodoItem("1", "Task A", TodoItem.Status.pending),
                new TodoItem("2", "Task B", TodoItem.Status.completed),
                new TodoItem("3", "Task C", TodoItem.Status.cancelled)
        ));
        String formatted = todoStore.formatForInjection("session-1");
        assertTrue(formatted.contains("Task A"));
        assertFalse(formatted.contains("Task B"));
        assertFalse(formatted.contains("Task C"));
    }

    @Test
    void formatForInjection_allCompleted_returnsEmpty() {
        todoStore.replace("session-1", List.of(
                new TodoItem("1", "Task A", TodoItem.Status.completed)
        ));
        assertTrue(todoStore.formatForInjection("session-1").isEmpty());
    }

    @Test
    void toolProviderRegistersTodoTool() {
        var tools = todoTool.getTools();
        assertEquals(1, tools.size());
        assertEquals("todo", tools.get(0).name());
    }
}
