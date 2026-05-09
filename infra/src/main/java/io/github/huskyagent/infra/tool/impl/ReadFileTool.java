package io.github.huskyagent.infra.tool.impl;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.huskyagent.infra.config.ToolLimitsConfig;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.tool.state.ToolStateStore;
import io.github.huskyagent.infra.workspace.Workspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReadFileTool implements ToolProvider {

    private final ToolStateStore toolStateStore;
    private final ToolLimitsConfig limitsConfig;
    private final Workspace workspace;

    record Args(
        @JsonPropertyDescription("Path to the file to read (absolute, relative, or ~/path)")
        String path,
        @JsonPropertyDescription("Line number to start reading from (1-indexed, default: 1)")
        Integer offset,
        @JsonPropertyDescription("Maximum number of lines to read (default: 500, max: 2000)")
        Integer limit
    ) {}

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(ToolDefinition.of("read_file",
            "Read a text file with line numbers and pagination. Use this instead of cat/head/tail in terminal. " +
            "Use offset and limit for large files. Cannot read binary files. " +
            "Suggests similar filenames if not found.",
            Toolset.CORE, Args.class, this::handle));
    }

    public ToolResult handle(Map<String, Object> args) {
        String path = (String) args.get("path");
        int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 1;
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 500;
        limit = Math.min(limit, limitsConfig.getReadFileMaxLines());

        if (path == null || path.isEmpty()) {
            return ToolResult.failure("Path is required");
        }

        if (FileUtils.BLOCKED_DEVICE_PATHS.contains(path)) {
            return ToolResult.failure("Cannot read device file");
        }

        try {
            Path filePath = workspace.resolve(path);

            if (!workspace.exists(filePath)) {
                String suggestions = FileUtils.suggestSimilarFiles(filePath);
                String msg = "File not found: " + path;
                if (!suggestions.isEmpty()) {
                    msg += "\nDid you mean one of these?\n" + suggestions;
                }
                return ToolResult.failure(msg);
            }

            if (FileUtils.isBinaryFile(filePath)) {
                return ToolResult.failure("Cannot read binary file: " + path);
            }

            long fileSize = workspace.size(filePath);
            if (fileSize > limitsConfig.getReadFileMaxChars()) {
                int totalLines = countLines(filePath);
                // Large files must be paged so tool output stays bounded and clickable in the UI.
                return ToolResult.failure(
                    "File too large (" + fileSize + " bytes, ~" + totalLines + " lines). " +
                    "Use offset and limit to read specific sections. " +
                    "Example: read_file(path=\"" + path + "\", offset=1, limit=100)");
            }

            List<String> lines = workspace.readAllLines(filePath);
            int totalLines = lines.size();

            int startLine = Math.max(0, offset - 1);
            int endLine = Math.min(startLine + limit, totalLines);

            StringBuilder result = new StringBuilder();
            int maxLineLen = 2000;
            for (int i = startLine; i < endLine; i++) {
                String line = lines.get(i);
                if (line.length() > maxLineLen) {
                    line = line.substring(0, maxLineLen) + "... [truncated]";
                }
                result.append(i + 1).append("\t").append(line).append("\n");
            }

            if (result.length() > limitsConfig.getReadFileMaxChars()) {
                result.setLength(limitsConfig.getReadFileMaxChars());
                result.append("\n[TRUNCATED]");
            }

            String sessionId = (String) args.get(ToolCallbackFactory.SESSION_ID_KEY);
            toolStateStore.markRead(sessionId, path);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("content", result.toString());
            output.put("totalLines", totalLines);

            return ToolResult.success(output);

        } catch (IOException e) {
            return ToolResult.failure("Failed to read: " + e.getMessage());
        }
    }

    private int countLines(Path file) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(workspace.newInputStream(file), StandardCharsets.UTF_8))) {
            int count = 0;
            while (reader.readLine() != null) count++;
            return count;
        }
    }
}
