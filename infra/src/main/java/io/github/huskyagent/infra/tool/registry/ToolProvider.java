package io.github.huskyagent.infra.tool.registry;

import java.util.List;

public interface ToolProvider {

    List<ToolDefinition> getTools();
}
