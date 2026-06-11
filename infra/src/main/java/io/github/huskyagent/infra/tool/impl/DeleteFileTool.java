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
public class DeleteFileTool implements ToolProvider {

    private final Workspace workspace;

    record Args(
        @JsonPropertyDescription("Path to the file to delete")
        String path
    ) {}

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(ToolDefinition.of("delete_file",
            "Delete a file. Use this instead of rm in terminal for single files. Refuses to delete directories or sensitive system paths.",
            Toolset.CORE, Args.class, this::handle));
    }

    public ToolResult handle(Map<String, Object> args) {
        String path = (String) args.get("path");

        if (path == null || path.isEmpty()) {
            return ToolResult.failure("path is required");
        }

        try {
            Path filePath = FileSafety.resolve(workspace, path);
            String denied = FileSafety.checkMutationAllowed(workspace, path, filePath);
            if (denied != null) {
                return ToolResult.failure(denied.replace("Mutation denied", "Delete denied"));
            }

            if (!workspace.exists(filePath)) {
                return ToolResult.failure("File not found: " + path);
            }

            if (workspace.isDirectory(filePath)) {
                return ToolResult.failure("Cannot delete directory with delete_file. Use terminal tool for directories.");
            }

            workspace.delete(filePath);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("success", true);
            output.put("deleted", path);
            return ToolResult.success(output);

        } catch (IOException e) {
            return ToolResult.failure("Failed to delete: " + e.getMessage());
        }
    }
}
