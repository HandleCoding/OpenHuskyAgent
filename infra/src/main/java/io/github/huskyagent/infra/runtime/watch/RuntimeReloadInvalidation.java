package io.github.huskyagent.infra.runtime.watch;

public interface RuntimeReloadInvalidation {

    void clearPromptCache();

    void clearGraphCache();
}
