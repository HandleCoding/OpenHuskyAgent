package io.github.huskyagent.infra.tool.registry;

import java.util.List;

/**
 * 工具提供者接口
 *
 * 实现此接口的 Spring bean 会被 ToolRegistry 自动发现并注册其工具。
 * 职责分离：记忆/业务逻辑在各自的 Provider 中，工具暴露在对应的 ToolProvider 中。
 */
public interface ToolProvider {

    List<ToolDefinition> getTools();
}
