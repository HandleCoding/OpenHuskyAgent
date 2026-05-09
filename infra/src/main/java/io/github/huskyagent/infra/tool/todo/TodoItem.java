package io.github.huskyagent.infra.tool.todo;

public record TodoItem(
        String id,
        String content,
        Status status
) {
    public enum Status {
        pending,
        in_progress,
        completed,
        cancelled
    }

    public TodoItem withStatus(Status newStatus) {
        return new TodoItem(id, content, newStatus);
    }
}
