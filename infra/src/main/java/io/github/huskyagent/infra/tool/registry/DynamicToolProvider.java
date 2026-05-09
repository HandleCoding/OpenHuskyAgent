package io.github.huskyagent.infra.tool.registry;

/**
 * 支持运行时变更通知的 ToolProvider。
 */
public interface DynamicToolProvider extends ToolProvider {

    /**
     * provider 的稳定标识，用于 ToolRegistry 做原子替换。
     */
    String providerKey();

    /**
     * 注册工具变更回调。provider 内部工具集变化后应调用该回调。
     */
    void setToolsChangedListener(Runnable listener);
}
