package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;

public class GatewaySection extends AbstractPromptSection {

    @Override
    public String getName() {
        return "gateway";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public String build(PromptContext context) {
        return context.getGatewaySystemPrompt()
            .map(prompt -> prompt + "\n\n")
            .orElse("");
    }
}