package io.github.huskyagent.infra.tool.impl;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.workspace.Workspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MoveFileTool implements ToolProvider {

    private final Workspace workspace;

    record Args(
        @JsonPropertyDescription("Source file path")
        String source,
        @JsonPropertyDescription("Destination file path")
        String destination
    ) {}

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(ToolDefinition.of("move_file",
            "Move or rename a file. Use this instead of mv in terminal. Creates destination parent directories. Refuses to move to/from sensitive paths.",
            Toolset.CORE, Args.class, this::handle));
    }

    public ToolResult handle(Map<String, Object> args) {
        String source = (String) args.get("source");
        String destination = (String) args.get("destination");

        if (source == null || destination == null) {
            return ToolResult.failure("source and destination required");
        }

        try {
            Path srcPath = workspace.resolve(source);
            Path dstPath = workspace.resolve(destination);

            if (FileUtils.isSensitivePath(srcPath) || FileUtils.isSensitivePath(dstPath)) {
                return ToolResult.failure("Move denied: protected system path involved. Use terminal tool if needed.");
            }

            if (!workspace.exists(srcPath)) {
                return ToolResult.failure("Source file not found: " + source);
            }

            Path dstParent = dstPath.getParent();
            if (dstParent != null && !workspace.exists(dstParent)) {
                workspace.createDirectories(dstParent);
            }

            workspace.move(srcPath, dstPath);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("success", true);
            output.put("moved_from", source);
            output.put("moved_to", destination);
            return ToolResult.success(output);

        } catch (IOException e) {
            return ToolResult.failure("Failed to move: " + e.getMessage());
        }
    }
}
