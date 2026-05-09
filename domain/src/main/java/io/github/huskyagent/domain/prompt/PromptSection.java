package io.github.huskyagent.domain.prompt;

public interface PromptSection {

    String getName();

    int getPriority();

    boolean isEnabled();

    String build(PromptContext context);

    default boolean isDynamic() {
        return false;
    }
}
