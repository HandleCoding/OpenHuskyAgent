package io.github.huskyagent.infra.tool.registry;

public interface DynamicToolProvider extends ToolProvider {

    String providerKey();

    void setToolsChangedListener(Runnable listener);
}
