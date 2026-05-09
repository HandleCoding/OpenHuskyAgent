package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.infra.tool.todo.TodoStore;

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
