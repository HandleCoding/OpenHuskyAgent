package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;

import java.util.function.Function;

/**
 * MCP Section — 注入 MCP (Model Context Protocol) 工具描述到系统提示
 */
public class McpSection extends AbstractPromptSection {

    private String mcpToolsDescription;
    private Function<PromptContext, String> descriptionSupplier;

    public McpSection() {
        setEnabled(false);
    }

    @Override
    public String getName() {
        return "mcp";
    }

    @Override
    public int getPriority() {
        return 550;  // 在 Tools 之后
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public String build(PromptContext context) {
        refreshDescription(context);
        if (mcpToolsDescription == null || mcpToolsDescription.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## MCP Tools\n\n");
        sb.append("[System note: Tools provided by MCP (Model Context Protocol) servers]\n\n");
        sb.append(mcpToolsDescription);
        sb.append("\n\n");
        return sb.toString();
    }

    /**
     * 设置 MCP 工具描述。非空时自动启用此 section。
     */
    public void setMcpToolsDescription(String description) {
        this.mcpToolsDescription = description;
        setEnabled(description != null && !description.isBlank());
    }

    public void setDescriptionSupplier(Function<PromptContext, String> descriptionSupplier) {
        this.descriptionSupplier = descriptionSupplier;
        setEnabled(descriptionSupplier != null);
    }

    private void refreshDescription(PromptContext context) {
        if (descriptionSupplier != null) {
            setMcpToolsDescription(descriptionSupplier.apply(context));
        }
    }
}
