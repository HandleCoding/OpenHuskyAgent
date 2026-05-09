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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchFilesTool implements ToolProvider {

    private final Workspace workspace;

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    record Args(
        @JsonPropertyDescription(
            "Content search: regex pattern. File search: glob pattern like '*.java' or '*config*' — NOT regex.")
        String pattern,
        @JsonPropertyDescription(
            "'content': regex search inside files (like grep). 'files': find files by name glob (like find -name). Default: content")
        String target,
        @JsonPropertyDescription("Directory to search in. Default: current directory")
        String path,
        @JsonPropertyDescription("File filter in content mode (e.g. *.java)")
        String glob,
        @JsonPropertyDescription(
            "Output format: 'content' (matches with lines), 'files_only' (file paths), 'count' (match counts per file). Default: content")
        String output_mode,
        @JsonPropertyDescription("Context lines before and after each match (content mode only). Default: 0")
        Integer context,
        @JsonPropertyDescription("Case-insensitive search. Default: false")
        Boolean case_insensitive,
        @JsonPropertyDescription("Skip first N results for pagination. Default: 0")
        Integer offset,
        @JsonPropertyDescription("Max results to return. Default: 50")
        Integer limit
    ) {}

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(ToolDefinition.of("search_files",
            "Search file contents or find files by name. Use this instead of grep/find/ls in terminal.\n\n" +
            "Content search (target='content'): Regex search inside files. Output modes: content, files_only, count.\n" +
            "File search (target='files'): Find files by glob pattern (e.g., '*.java', '*config*'). Results sorted by modification time.",
            Toolset.SEARCH, Args.class, this::handle));
    }

    public ToolResult handle(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        String target = args.containsKey("target") ? (String) args.get("target") : "content";
        String searchPath = args.containsKey("path") ? (String) args.get("path") : ".";
        String glob = (String) args.get("glob");
        String outputMode = args.containsKey("output_mode") ? (String) args.get("output_mode") : "content";
        int contextLines = args.containsKey("context") ? ((Number) args.get("context")).intValue() : 0;
        boolean caseInsensitive = Boolean.TRUE.equals(args.get("case_insensitive"));
        int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 0;
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : DEFAULT_LIMIT;

        if (pattern == null || pattern.isBlank()) {
            return ToolResult.failure("pattern required");
        }
        if (!List.of("content", "files").contains(target)) {
            return ToolResult.failure("target must be 'content' or 'files'");
        }
        if (!List.of("content", "files_only", "count").contains(outputMode)) {
            return ToolResult.failure("output_mode must be 'content', 'files_only', or 'count'");
        }
        if (offset < 0) {
            return ToolResult.failure("offset must be >= 0");
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            return ToolResult.failure("limit must be between 1 and " + MAX_LIMIT);
        }
        if (contextLines < 0) {
            return ToolResult.failure("context must be >= 0");
        }

        try {
            Path searchDir = workspace.resolve(searchPath);

            if ("files".equals(target)) {
                return handleSearchByFilename(pattern, searchDir, limit, offset);
            }

            Pattern regex;
            try {
                int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
                regex = Pattern.compile(pattern, flags);
            } catch (java.util.regex.PatternSyntaxException e) {
                return ToolResult.failure("Invalid regex pattern: " + e.getMessage());
            }

            Predicate<Path> fileFilter = FileUtils.globMatcher(searchDir, glob);
            List<Map<String, Object>> allMatches = new ArrayList<>();
            Map<String, Integer> fileCounts = new LinkedHashMap<>();

            workspace.walkFiles(searchDir)
                .stream()
                .filter(fileFilter)
                .filter(p -> !FileUtils.isBinaryFile(p))
                .forEach(file -> searchFile(file, regex, outputMode, contextLines, allMatches, fileCounts));

            if ("files_only".equals(outputMode)) {
                List<String> allFiles = allMatches.stream()
                    .map(m -> (String) m.get("file"))
                    .distinct()
                    .collect(Collectors.toList());
                List<String> page = allFiles.stream()
                    .skip(offset)
                    .limit(limit)
                    .collect(Collectors.toList());
                Map<String, Object> result = pagedResult("files", page, allFiles.size(), offset, limit);
                return ToolResult.success(result);
            } else if ("count".equals(outputMode)) {
                int totalMatches = fileCounts.values().stream().mapToInt(Integer::intValue).sum();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("counts", fileCounts);
                result.put("totalMatches", totalMatches);
                result.put("totalFiles", fileCounts.size());
                return ToolResult.success(result);
            } else {
                List<Map<String, Object>> page = allMatches.stream()
                    .skip(offset)
                    .limit(limit)
                    .collect(Collectors.toList());
                Map<String, Object> result = pagedResult("matches", page, allMatches.size(), offset, limit);
                return ToolResult.success(result);
            }

        } catch (Exception e) {
            return ToolResult.failure("Search failed: " + e.getMessage());
        }
    }

    private void searchFile(Path file, Pattern regex, String outputMode, int contextLines,
                            List<Map<String, Object>> allMatches, Map<String, Integer> fileCounts) {
        try {
            List<String> lines = workspace.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (regex.matcher(lines.get(i)).find()) {
                    if ("count".equals(outputMode)) {
                        fileCounts.merge(file.toString(), 1, Integer::sum);
                    } else {
                        allMatches.add(matchResult(file, lines, i, contextLines, outputMode));
                    }
                }
            }
        } catch (IOException e) {
            // skip unreadable files
        }
    }

    private Map<String, Object> matchResult(Path file, List<String> lines, int lineIndex,
                                            int contextLines, String outputMode) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("file", file.toString());
        match.put("line", lineIndex + 1);
        match.put("content", lines.get(lineIndex));

        if (contextLines > 0 && "content".equals(outputMode)) {
            List<String> before = new ArrayList<>();
            List<String> after = new ArrayList<>();
            for (int c = Math.max(0, lineIndex - contextLines); c < lineIndex; c++) {
                before.add(lines.get(c));
            }
            for (int c = lineIndex + 1; c < Math.min(lines.size(), lineIndex + 1 + contextLines); c++) {
                after.add(lines.get(c));
            }
            if (!before.isEmpty()) match.put("context_before", before);
            if (!after.isEmpty()) match.put("context_after", after);
        }

        return match;
    }

    private ToolResult handleSearchByFilename(String pattern, Path searchDir, int limit, int offset) throws IOException {
        Predicate<Path> matcher = FileUtils.globMatcher(searchDir, normalizeFilePattern(pattern));
        List<String> allFiles = workspace.walkFiles(searchDir)
            .stream()
            .filter(matcher)
            .sorted((a, b) -> {
                try {
                    return -Long.compare(workspace.getLastModifiedTime(a), workspace.getLastModifiedTime(b));
                } catch (IOException e) { return 0; }
            })
            .map(Path::toString)
            .collect(Collectors.toList());

        List<String> page = allFiles.stream()
            .skip(offset)
            .limit(limit)
            .collect(Collectors.toList());

        return ToolResult.success(pagedResult("files", page, allFiles.size(), offset, limit));
    }

    private String normalizeFilePattern(String pattern) {
        if (!pattern.contains("/") && !pattern.contains("**") && !pattern.contains("*") && !pattern.contains("?")) {
            return "*" + pattern + "*";
        }
        return pattern;
    }

    private Map<String, Object> pagedResult(String key, Object page, int total, int offset, int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, page);
        result.put("total", total);
        result.put("returned", page instanceof List<?> list ? list.size() : 0);
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("truncated", total > offset + limit);
        return result;
    }
}
