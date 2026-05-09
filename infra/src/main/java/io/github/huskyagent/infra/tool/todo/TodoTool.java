package io.github.huskyagent.infra.tool.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Todo 任务追踪工具 — 对标 Hermes todo_tool
 *
 * <p>单次调用批量写入任务列表，避免并发多次 create。</p>
 *
 * <ul>
 *   <li>提供 todos 数组 → 写入（merge=false 整体替换，merge=true 按 id 合并）</li>
 *   <li>不提供 todos → 读取当前列表</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoTool implements ToolProvider {

    private final TodoStore todoStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(buildDefinition());
    }

    private ToolDefinition buildDefinition() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");

        // todos 数组
        ObjectNode todosNode = props.putObject("todos");
        todosNode.put("type", "array");
        todosNode.put("description", "Task items to write. Omit to read current list.");
        ObjectNode itemsNode = todosNode.putObject("items");
        itemsNode.put("type", "object");
        ObjectNode itemProps = itemsNode.putObject("properties");

        ObjectNode idProp = itemProps.putObject("id");
        idProp.put("type", "string");
        idProp.put("description", "Unique item identifier");

        ObjectNode contentProp = itemProps.putObject("content");
        contentProp.put("type", "string");
        contentProp.put("description", "Task description");

        ObjectNode statusProp = itemProps.putObject("status");
        statusProp.put("type", "string");
        statusProp.put("description", "Current status");
        ArrayNode statusEnum = statusProp.putArray("enum");
        statusEnum.add("pending").add("in_progress").add("completed").add("cancelled");

        ArrayNode itemRequired = itemsNode.putArray("required");
        itemRequired.add("id").add("content").add("status");

        // merge 布尔值
        ObjectNode mergeNode = props.putObject("merge");
        mergeNode.put("type", "boolean");
        mergeNode.put("description", "true: update existing items by id, add new ones. false (default): replace entire list.");
        mergeNode.put("default", false);

        return ToolDefinition.of("todo",
                """
                Manage your task list for the current session. Use for complex tasks \
                with 3+ steps or when the user provides multiple tasks. \
                Call with no parameters to read the current list.

                Writing:
                - Provide 'todos' array to create/update items
                - merge=false (default): replace the entire list with a fresh plan
                - merge=true: update existing items by id, add any new ones

                Each item: {id: string, content: string, status: pending|in_progress|completed|cancelled}
                List order is priority. Only ONE item in_progress at a time.
                Mark items completed immediately when done. If something fails, \
                cancel it and add a revised item.
                After context compression, your active tasks are re-injected — \
                do NOT redo completed work.""",
                Toolset.CORE, schema, this::handle);
    }

    @SuppressWarnings("unchecked")
    public ToolResult handle(Map<String, Object> args) {
        String sessionId = (String) args.get(ToolCallbackFactory.SESSION_ID_KEY);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "__default__";
        }

        Object todosObj = args.get("todos");
        boolean merge = args.containsKey("merge") && Boolean.TRUE.equals(args.get("merge"));

        if (todosObj == null) {
            // 读取模式：不提供 todos 时返回当前列表
            return handleList(sessionId);
        }

        // 写入模式：解析 todos 数组
        List<Map<String, Object>> todoMaps;
        if (todosObj instanceof List<?> list) {
            todoMaps = (List<Map<String, Object>>) (List<?>) list;
        } else {
            return ToolResult.failure("todos must be an array");
        }

        List<TodoItem> items = new ArrayList<>();
        for (Map<String, Object> t : todoMaps) {
            String id = (String) t.get("id");
            String content = (String) t.get("content");
            String statusStr = (String) t.get("status");
            if (id == null || content == null || statusStr == null) continue;
            try {
                TodoItem.Status status = TodoItem.Status.valueOf(statusStr);
                items.add(new TodoItem(id, content, status));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid status '{}' in todo item, skipping", statusStr);
            }
        }

        if (merge) {
            todoStore.merge(sessionId, items);
        } else {
            todoStore.replace(sessionId, items);
        }

        return handleList(sessionId);
    }

    private ToolResult handleList(String sessionId) {
        List<TodoItem> items = todoStore.list(sessionId);
        if (items.isEmpty()) {
            return ToolResult.success("No tasks in current session.");
        }

        int pending = 0, inProgress = 0, completed = 0, cancelled = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Tasks (").append(items.size()).append("):\n");
        for (TodoItem item : items) {
            sb.append("  [").append(item.id()).append("] ");
            switch (item.status()) {
                case pending     -> { sb.append("⏳"); pending++; }
                case in_progress -> { sb.append("🔄"); inProgress++; }
                case completed   -> { sb.append("✅"); completed++; }
                case cancelled   -> { sb.append("❌"); cancelled++; }
            };
            sb.append(" ").append(item.content()).append("\n");
        }
        sb.append("Summary: ").append(completed).append(" completed, ")
                .append(inProgress).append(" in_progress, ")
                .append(pending).append(" pending, ")
                .append(cancelled).append(" cancelled");
        return ToolResult.success(sb.toString());
    }
}
