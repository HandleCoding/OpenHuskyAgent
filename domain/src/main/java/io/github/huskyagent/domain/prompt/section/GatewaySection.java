package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;

/**
 * Gateway System Prompt Section
 *
 * 注入来自 Gateway（Telegram/Discord/Slack 等）的系统提示
 */
public class GatewaySection extends AbstractPromptSection {

    @Override
    public String getName() {
        return "gateway";
    }

    @Override
    public int getPriority() {
        return 100;  // Identity 之后
    }

    @Override
    public String build(PromptContext context) {
        return context.getGatewaySystemPrompt()
            .map(prompt -> prompt + "\n\n")
            .orElse("");
    }
}