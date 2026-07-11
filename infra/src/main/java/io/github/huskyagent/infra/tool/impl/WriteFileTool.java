package io.github.huskyagent.infra.tool.impl;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.tool.adapter.ToolExecutionContext;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.tool.state.ToolStateStore;
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
public class WriteFileTool implements ToolProvider {

    private final ToolStateStore toolStateStore;
    private final Workspace workspace;

    record Args(
        @JsonPropertyDescription("Path to the file to write (will be created if it doesn't exist, overwritten if it does)")
        String path,
        @JsonPropertyDescription("Complete content to write to the file")
        String content
    ) {}

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(ToolDefinition.contextual("write_file",
            "Write content to a new file or completely replace an existing file. Use this instead of echo/cat heredoc in terminal. " +
            "Creates parent directories automatically. OVERWRITES the entire file — use edit_file for small replacements and apply_patch for multi-line or multi-file edits.",
            Toolset.CORE, Args.class, this::handle));
    }

    public ToolResult handle(Map<String, Object> args) {
        return handle(args, FileToolRuntime.localContext(workspace));
    }

    public ToolResult handle(Map<String, Object> args, ToolExecutionContext context) {
        String path = (String) args.get("path");
        String content = (String) args.get("content");

        if (path == null || path.isEmpty()) {
            return ToolResult.failure("Path is required");
        }

        try {
            Workspace workspace = FileToolRuntime.workspace(context, this.workspace);
            Path filePath = FileSafety.resolve(workspace, path);
            String denied = FileSafety.checkWriteAllowed(workspace, path, filePath);
            if (denied != null) {
                return ToolResult.failure(denied);
            }

            String warning = null;
            String sessionId = (String) args.get(ToolCallbackFactory.SESSION_ID_KEY);
            Path canonicalPath = FileSafety.canonicalForAccess(workspace, filePath);
            warning = toolStateStore.checkBeforeWrite(sessionId, canonicalPath, workspace, true);
            if (warning != null) {
                log.warn(warning);
            }

            Path parent = filePath.getParent();
            if (parent != null && !workspace.exists(parent)) {
                workspace.createDirectories(parent);
            }

            workspace.writeString(filePath, content != null ? content : "");

            if (sessionId != null) {
                Path writtenPath = FileSafety.canonicalForAccess(workspace, filePath);
                toolStateStore.markWritten(sessionId, writtenPath, workspace);
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("success", true);
            output.put("bytesWritten", content != null ? content.length() : 0);
            if (warning != null) {
                output.put("warning", warning);
            }

            return ToolResult.success(output);

        } catch (IllegalStateException e) {
            return ToolResult.failure(e.getMessage());
        } catch (IOException e) {
            return ToolResult.failure("Failed to write: " + e.getMessage());
        }
    }
}
