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

    private record ReadWindow(String content, int totalLines, boolean partialRead) {}

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
        if (offset < 1) {
            return ToolResult.failure("offset must be >= 1");
        }
        if (limit < 1) {
            return ToolResult.failure("limit must be >= 1");
        }

        try {
            Path filePath = FileSafety.resolve(workspace, path);
            String denied = FileSafety.checkReadAllowed(workspace, path, filePath);
            if (denied != null) {
                return ToolResult.failure(denied);
            }

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

            ReadWindow window = readWindow(filePath, offset, limit);

            String sessionId = (String) args.get(ToolCallbackFactory.SESSION_ID_KEY);
            Path canonicalPath = FileSafety.canonicalForAccess(workspace, filePath);
            toolStateStore.markRead(sessionId, canonicalPath, workspace.getLastModifiedTime(filePath), window.partialRead());

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("content", window.content());
            output.put("totalLines", window.totalLines());

            return ToolResult.success(output);

        } catch (IOException e) {
            return ToolResult.failure("Failed to read: " + e.getMessage());
        }
    }

    private ReadWindow readWindow(Path file, int offset, int limit) throws IOException {
        StringBuilder result = new StringBuilder();
        int maxLineLen = 2000;
        int startLine = offset;
        int endLine = offset + limit - 1;
        int lineNumber = 0;
        boolean outputTruncated = false;

        try (var reader = new BufferedReader(new InputStreamReader(workspace.newInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < startLine || lineNumber > endLine) {
                    continue;
                }
                if (outputTruncated) {
                    continue;
                }

                if (line.length() > maxLineLen) {
                    line = line.substring(0, maxLineLen) + "... [truncated]";
                }
                result.append(lineNumber).append("\t").append(line).append("\n");

                if (result.length() > limitsConfig.getReadFileMaxChars()) {
                    result.setLength(limitsConfig.getReadFileMaxChars());
                    result.append("\n[TRUNCATED]");
                    outputTruncated = true;
                }
            }
        }

        boolean partial = startLine > 1 || lineNumber > endLine || outputTruncated;
        return new ReadWindow(result.toString(), lineNumber, partial);
    }
}
