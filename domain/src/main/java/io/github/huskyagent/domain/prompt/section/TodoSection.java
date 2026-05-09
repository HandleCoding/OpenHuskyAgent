package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.infra.tool.todo.TodoStore;

/**
 * Todo 任务注入 Section
 *
 * <p>在系统提示中注入当前会话的活跃任务列表，
 * 让 Agent 在长任务中不会遗忘未完成的工作。</p>
 *
 * <p>Context compression 后仍可通过此 Section 重新注入。</p>
 */
public class TodoSection extends AbstractPromptSection {

    private final TodoStore todoStore;

    public TodoSection(TodoStore todoStore) {
        this.todoStore = todoStore;
    }

    @Override
    public String getName() {
        return "todo";
    }

    @Override
    public int getPriority() {
        return 560;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public String build(PromptContext context) {
        String injection = todoStore.formatForInjection(context.getSessionId());
        if (injection.isEmpty()) {
            return "";
        }
        return buildWithTitle("Active Tasks", injection);
    }
}
