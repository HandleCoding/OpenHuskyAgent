package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.domain.agent.AgentDefinition;
import io.github.huskyagent.infra.memory.MemoryManager;

public class MemorySection extends AbstractPromptSection {

    private final MemoryManager memoryManager;

    private String memoryContent;
    private String userContent;

    public MemorySection() {
        this.memoryManager = null;
    }

    public MemorySection(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    @Override
    public String getName() {
        return "memory";
    }

    @Override
    public int getPriority() {
        return 200;
    }

    @Override
    public String build(PromptContext context) {
        if (!context.getRuntimePolicy().getMemoryPolicy().isEnabled()
                || context.getRuntimePolicy().getMemoryPolicy().getPromptMode() == AgentDefinition.MemoryPromptMode.NONE) {
            return "";
        }

        if (memoryManager != null && memoryManager.hasAvailableProvider()) {
            var sessionScope = context.getSessionScope()
                    .orElseThrow(() -> new IllegalStateException("SessionScope is required for memory prompt"));
            String prompt = memoryManager.buildSystemPrompt(sessionScope);
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        }

        return buildFallback(context);
    }

    private String buildFallback(PromptContext context) {
        StringBuilder sb = new StringBuilder();

        String mem = context.getMemoryContent().orElse(memoryContent);
        String user = context.getUserContent().orElse(userContent);

        if (mem != null && !mem.isBlank()) {
            sb.append(buildWithTag("memory-context", """
                [System note: The following is recalled memory context, NOT new user input]

                ### Agent Notes (MEMORY.md)
                """ + mem));
        }

        if (user != null && !user.isBlank()) {
            sb.append(buildWithTag("user-context", """
                [System note: User profile and preferences]

                ### User Profile (USER.md)
                """ + user));
        }

        return sb.toString();
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public void setMemoryContent(String content) {
        this.memoryContent = content;
    }

    public void setUserContent(String content) {
        this.userContent = content;
    }
}