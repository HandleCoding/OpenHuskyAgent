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
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListFilesTool implements ToolProvider {

    private final Workspace workspace;

    private static final int DEFAULT_LIMIT = 100;

    record Args(
        @JsonPropertyDescription("Directory path to recursively list files from (default: current directory)")
        String path,
        @JsonPropertyDescription("Glob pattern for file names or root-relative paths, e.g. '*.java' or 'src/**/*.java' (default: *)")
        String glob
    ) {}

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(ToolDefinition.of("list_files",
            "Recursively list regular files under a directory using a glob matched against file names or root-relative paths. Use search_files for content search.",
            Toolset.SEARCH, Args.class, this::handle));
    }

    public ToolResult handle(Map<String, Object> args) {
        String searchPath = args.containsKey("path") ? (String) args.get("path") : ".";
        String glob = args.containsKey("glob") ? (String) args.get("glob") : "*";

        try {
            Path searchDir = workspace.resolve(searchPath);
            Predicate<Path> matcher = FileUtils.globMatcher(searchDir, glob);

            List<Path> matchedFiles = workspace.walkFiles(searchDir)
                .stream()
                .filter(matcher)
                .sorted((a, b) -> {
                    try {
                        return -Long.compare(
                            workspace.getLastModifiedTime(a),
                            workspace.getLastModifiedTime(b));
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .collect(Collectors.toList());

            List<Map<String, Object>> files = matchedFiles.stream()
                .limit(DEFAULT_LIMIT)
                .map(this::fileMetadata)
                .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("files", files);
            result.put("total", matchedFiles.size());
            result.put("returned", files.size());
            result.put("offset", 0);
            result.put("limit", DEFAULT_LIMIT);
            result.put("truncated", matchedFiles.size() > DEFAULT_LIMIT);
            return ToolResult.success(result);

        } catch (Exception e) {
            return ToolResult.failure("List failed: " + e.getMessage());
        }
    }

    private Map<String, Object> fileMetadata(Path path) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", path.toString());
        metadata.put("name", path.getFileName().toString());
        try {
            metadata.put("size", workspace.size(path));
            metadata.put("lastModified", workspace.getLastModifiedTime(path));
            metadata.put("isDirectory", false);
        } catch (IOException e) {
            // skip optional metadata
        }
        return metadata;
    }
}
