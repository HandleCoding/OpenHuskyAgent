package io.github.huskyagent.application.runtime;

import io.github.huskyagent.application.ReActAgentApp;
import io.github.huskyagent.domain.prompt.PromptBuilder;
import io.github.huskyagent.infra.runtime.watch.RuntimeReloadInvalidation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RuntimeReloadInvalidationAdapter implements RuntimeReloadInvalidation {

    private final PromptBuilder promptBuilder;
    private final ReActAgentApp reActAgentApp;

    @Override
    public void clearPromptCache() {
        promptBuilder.clearCache();
    }

    @Override
    public void clearGraphCache() {
        reActAgentApp.clearGraphCache();
    }
}
