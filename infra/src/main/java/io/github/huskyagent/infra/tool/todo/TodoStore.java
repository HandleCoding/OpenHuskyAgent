package io.github.huskyagent.infra.tool.todo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Todo 存储 — 按 sessionId 隔离，会话结束时清理
 */
@Slf4j
@Component
public class TodoStore {

    private final ConcurrentHashMap<String, List<TodoItem>> store = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    public List<TodoItem> list(String sessionId) {
        if (sessionId == null) return List.of();
        return List.copyOf(store.getOrDefault(sessionId, List.of()));
    }

    public TodoItem add(String sessionId, String content) {
        TodoItem item = new TodoItem(
                String.valueOf(idCounter.incrementAndGet()),
                content,
                TodoItem.Status.pending
        );
        store.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(item);
        log.debug("Todo added: session={}, id={}, content={}", sessionId, item.id(), content);
        return item;
    }

    public Optional<TodoItem> update(String sessionId, String id, TodoItem.Status newStatus) {
        List<TodoItem> items = store.get(sessionId);
        if (items == null) return Optional.empty();

        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(id)) {
                TodoItem updated = items.get(i).withStatus(newStatus);
                items.set(i, updated);
                log.debug("Todo updated: session={}, id={}, status={}", sessionId, id, newStatus);
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    public boolean remove(String sessionId, String id) {
        List<TodoItem> items = store.get(sessionId);
        if (items == null) return false;
        boolean removed = items.removeIf(item -> item.id().equals(id));
        if (removed) {
            log.debug("Todo removed: session={}, id={}", sessionId, id);
        }
        return removed;
    }

    public void clear(String sessionId) {
        store.remove(sessionId);
        log.debug("Todos cleared for session={}", sessionId);
    }

    /**
     * 整体替换任务列表（merge=false 模式）
     */
    public void replace(String sessionId, List<TodoItem> items) {
        store.put(sessionId, new ArrayList<>(items));
        log.debug("Todos replaced: session={}, count={}", sessionId, items.size());
    }

    /**
     * 合并任务列表（merge=true 模式）：按 id 更新已有项，追加新项
     */
    public void merge(String sessionId, List<TodoItem> items) {
        List<TodoItem> existing = store.computeIfAbsent(sessionId, k -> new ArrayList<>());
        Map<String, TodoItem> existingMap = new LinkedHashMap<>();
        for (TodoItem item : existing) {
            existingMap.put(item.id(), item);
        }
        for (TodoItem item : items) {
            existingMap.put(item.id(), item);
        }
        existing.clear();
        existing.addAll(existingMap.values());
        log.debug("Todos merged: session={}, count={}", sessionId, existing.size());
    }

    /**
     * 格式化当前会话的活跃 todo 列表，用于 prompt 注入
     *
     * <p>只注入 pending/in_progress 项，避免模型重复做已完成的工作。
     * 对标 Hermes：compression 后只注入 active tasks。</p>
     */
    public String formatForInjection(String sessionId) {
        if (sessionId == null) return "";
        List<TodoItem> items = store.getOrDefault(sessionId, List.of());
        if (items.isEmpty()) return "";

        List<TodoItem> activeItems = items.stream()
                .filter(item -> item.status() == TodoItem.Status.pending || item.status() == TodoItem.Status.in_progress)
                .toList();
        if (activeItems.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("[Your active task list was preserved]\n");
        for (TodoItem item : activeItems) {
            String marker = switch (item.status()) {
                case pending -> "[ ]";
                case in_progress -> "[>]";
                default -> "[?]";
            };
            sb.append("- ").append(marker).append(" ").append(item.id()).append(". ").append(item.content()).append("\n");
        }
        return sb.toString();
    }
}
