package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;

import java.util.List;

/**
 * Tool Section
 *
 * 动态生成可用工具列表说明
 */
public class ToolSection extends AbstractPromptSection {

    @Override
    public String getName() {
        return "tools";
    }

    @Override
    public int getPriority() {
        return 500;  // 在 Memory 和 Skills 之后
    }

    @Override
    public boolean isDynamic() {
        return true;  // 工具列表可能在启动后变化（如 memory 工具延迟注册）
    }

    @Override
    public String build(PromptContext context) {
        List<ToolDefinition> tools = context.getRuntimePolicy().getCapabilityView().getVisibleTools();
        if (tools.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Available Tools\n\n");
        sb.append("You have access to the following tools:\n\n");

        // 按工具集分组
        tools.stream()
            .collect(java.util.stream.Collectors.groupingBy(ToolDefinition::toolset))
            .forEach((toolset, toolList) -> {
                sb.append("### ").append(toolset.getName().toUpperCase()).append("\n");
                for (ToolDefinition tool : toolList) {
                    sb.append("- **").append(tool.name()).append("**");
                    if (tool.emoji() != null && !tool.emoji().isEmpty()) {
                        sb.append(" ").append(tool.emoji());
                    }
                    sb.append(": ").append(tool.description()).append("\n");
                }
                sb.append("\n");
            });

        sb.append("**Note**: Some operations may require user approval.\n\n");

        return sb.toString();
    }
}