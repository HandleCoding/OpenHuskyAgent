package io.github.huskyagent.infra.tool.impl;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.tool.match.FuzzyMatcher;
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
public class EditFileTool implements ToolProvider {

    private final ToolStateStore toolStateStore;
    private final Workspace workspace;

    record Args(
        @JsonPropertyDescription("File path to edit (required for replace mode)")
        String path,
        @JsonPropertyDescription("Text to find in the file. Must be unique unless replace_all=true. Include enough surrounding context to ensure uniqueness.")
        String old_string,
        @JsonPropertyDescription("Replacement text. Can be empty string to delete the matched text.")
        String new_string,
        @JsonPropertyDescription("Replace all occurrences instead of requiring a unique match (default: false)")
        Boolean replace_all
    ) {}

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(ToolDefinition.of("edit_file",
            "Precise single-file find-and-replace for small, unique edits. Use apply_patch for multi-line blocks, insertions, deletions, or multi-file changes. " +
            "Returns a unified diff. Must read_file before editing.",
            Toolset.CORE, Args.class, this::handle));
    }

    public ToolResult handle(Map<String, Object> args) {
        String path = (String) args.get("path");
        String oldString = (String) args.get("old_string");
        String newString = (String) args.get("new_string");
        boolean replaceAll = Boolean.TRUE.equals(args.get("replace_all"));

        if (path == null || oldString == null) {
            return ToolResult.failure("path and old_string required");
        }

        String sessionId = (String) args.get(ToolCallbackFactory.SESSION_ID_KEY);
        if (!toolStateStore.hasBeenRead(sessionId, path)) {
            return ToolResult.failure(
                    "Must call read_file on '" + path + "' before editing it.");
        }

        try {
            Path filePath = workspace.resolve(path);

            if (!workspace.exists(filePath)) {
                return ToolResult.failure("File not found: " + path);
            }

            String content = workspace.readString(filePath);

            FuzzyMatcher.MatchResult result = FuzzyMatcher.findAndReplaceStrict(
                content, oldString, newString != null ? newString : "", replaceAll);

            if (result.error() != null) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("error", result.error());
                output.put("hint", "Use read_file to verify current content, search_files to locate the text, or apply_patch for multi-line block edits.");
                return ToolResult.failure(result.error());
            }

            String newContent = result.newContent();
            workspace.writeString(filePath, newContent);

            String diff = FileUtils.generateDiff(content, newContent, path);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("success", true);
            output.put("replacements", result.matchCount());
            output.put("strategy", result.strategy());
            output.put("diff", diff);

            return ToolResult.success(output);

        } catch (IOException e) {
            return ToolResult.failure("Failed to edit: " + e.getMessage());
        }
    }
}
