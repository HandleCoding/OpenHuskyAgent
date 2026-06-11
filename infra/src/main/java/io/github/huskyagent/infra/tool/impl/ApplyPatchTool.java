package io.github.huskyagent.infra.tool.impl;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.github.huskyagent.infra.tool.Toolset;
import io.github.huskyagent.infra.tool.adapter.ToolCallbackFactory;
import io.github.huskyagent.infra.tool.match.FuzzyMatcher;
import io.github.huskyagent.infra.tool.match.V4aPatchParser;
import io.github.huskyagent.infra.tool.registry.ToolDefinition;
import io.github.huskyagent.infra.tool.registry.ToolProvider;
import io.github.huskyagent.infra.tool.registry.ToolResult;
import io.github.huskyagent.infra.tool.state.ToolStateStore;
import io.github.huskyagent.infra.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ApplyPatchTool implements ToolProvider {

    private final Workspace workspace;
    private final ToolStateStore toolStateStore;

    @Autowired
    public ApplyPatchTool(Workspace workspace, ToolStateStore toolStateStore) {
        this.workspace = workspace;
        this.toolStateStore = toolStateStore;
    }

    ApplyPatchTool(Workspace workspace) {
        this(workspace, new ToolStateStore());
    }

    record Args(
        @JsonPropertyDescription("""
            V4A/Codex-style patch. Required format:
            *** Begin Patch
            *** Update File: path/to/file
            -old line
            +new line
            *** End Patch

            Operations: Update File, Add File, Delete File, Move File.
            Line prefixes: space=context, -=remove, +=add.
            Empty lines in patch must match empty lines in the file; preserve blank lines exactly.
            Optional @@ context @@ hints label edit regions and can anchor addition-only hunks.""")
        String patch
    ) {}

    private record PreparedChange(Path path, String displayPath, String beforeContent, String afterContent, ChangeKind kind) {}

    private enum ChangeKind { ADD, UPDATE, DELETE }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(ToolDefinition.of("apply_patch",
            "Apply a V4A/Codex-style patch for multi-line or multi-file edits. " +
            "Format: '*** Begin Patch' ... '*** End Patch' with operations like " +
            "'*** Update File: path', '*** Add File: path', '*** Delete File: path', '*** Move File: old -> new'. " +
            "Line prefixes: space=context, -=remove, +=add. " +
            "Empty lines in patch must match empty lines in the file. " +
            "Optional @@ context @@ hints label edit regions and can anchor addition-only hunks. " +
            "For updates, context/removal lines are used for matching. " +
            "Validates the full patch before writing and returns a unified diff.",
            Toolset.CORE, Args.class, this::handle));
    }

    ToolResult handle(Map<String, Object> args) {
        String patchContent = (String) args.get("patch");

        if (patchContent == null || patchContent.isBlank()) {
            return ToolResult.failure("patch content required");
        }

        V4aPatchParser.ParseResult parseResult = V4aPatchParser.parse(patchContent);
        if (parseResult.error() != null) {
            return ToolResult.failure("Patch parse error: " + parseResult.error());
        }

        List<V4aPatchParser.PatchOperation> operations = parseResult.operations();
        if (operations.isEmpty()) {
            return ToolResult.failure("No operations found in patch");
        }

        try {
            List<PreparedChange> changes = prepareChanges(operations);
            String sessionId = (String) args.get(ToolCallbackFactory.SESSION_ID_KEY);
            return commitChanges(changes, sessionId);
        } catch (Exception e) {
            return ToolResult.failure("Patch validation failed (no files modified): " + e.getMessage());
        }
    }

    private List<PreparedChange> prepareChanges(List<V4aPatchParser.PatchOperation> operations) throws Exception {
        Map<Path, String> contentByPath = new LinkedHashMap<>();
        Map<Path, String> originalContentByPath = new LinkedHashMap<>();
        Map<Path, String> displayByPath = new LinkedHashMap<>();
        Set<Path> createdPaths = new LinkedHashSet<>();
        Set<Path> deletedPaths = new LinkedHashSet<>();

        for (V4aPatchParser.PatchOperation op : operations) {
            switch (op.operation()) {
                case UPDATE -> prepareUpdate(op, contentByPath, originalContentByPath, displayByPath, deletedPaths);
                case ADD -> prepareAdd(op, contentByPath, originalContentByPath, displayByPath, createdPaths, deletedPaths);
                case DELETE -> prepareDelete(op, contentByPath, originalContentByPath, displayByPath, createdPaths, deletedPaths);
                case MOVE -> prepareMove(op, contentByPath, originalContentByPath, displayByPath, createdPaths, deletedPaths);
            }
        }

        List<PreparedChange> changes = new ArrayList<>();
        for (Map.Entry<Path, String> entry : contentByPath.entrySet()) {
            Path path = entry.getKey();
            String before = originalContentByPath.get(path);
            String after = entry.getValue();
            ChangeKind kind = before == null ? ChangeKind.ADD : after == null ? ChangeKind.DELETE : ChangeKind.UPDATE;
            if (before == null || after == null || !before.equals(after)) {
                changes.add(new PreparedChange(path, displayByPath.get(path), before, after, kind));
            }
        }
        return changes;
    }

    private void prepareUpdate(V4aPatchParser.PatchOperation op,
                               Map<Path, String> contentByPath,
                               Map<Path, String> originalContentByPath,
                               Map<Path, String> displayByPath,
                               Set<Path> deletedPaths) throws Exception {
        Path filePath = resolveWritablePath(op.filePath());
        if (deletedPaths.contains(filePath)) throw new IllegalArgumentException(op.filePath() + ": file was already deleted in this patch");
        String current = currentContent(filePath, op.filePath(), contentByPath, originalContentByPath, displayByPath);
        String updated = applyHunks(current, op);
        contentByPath.put(filePath, updated);
    }

    private void prepareAdd(V4aPatchParser.PatchOperation op,
                            Map<Path, String> contentByPath,
                            Map<Path, String> originalContentByPath,
                            Map<Path, String> displayByPath,
                            Set<Path> createdPaths,
                            Set<Path> deletedPaths) throws Exception {
        Path filePath = resolveWritablePath(op.filePath());
        if (workspace.exists(filePath) || contentByPath.containsKey(filePath)) {
            throw new IllegalArgumentException(op.filePath() + ": file already exists for add");
        }
        if (deletedPaths.contains(filePath)) throw new IllegalArgumentException(op.filePath() + ": cannot add file deleted earlier in same patch");
        String content = op.hunks().stream()
                .flatMap(h -> h.lines().stream())
                .filter(l -> "+".equals(l.prefix()))
                .map(V4aPatchParser.HunkLine::content)
                .collect(Collectors.joining("\n"));
        contentByPath.put(filePath, content);
        originalContentByPath.put(filePath, null);
        displayByPath.put(filePath, op.filePath());
        createdPaths.add(filePath);
    }

    private void prepareDelete(V4aPatchParser.PatchOperation op,
                               Map<Path, String> contentByPath,
                               Map<Path, String> originalContentByPath,
                               Map<Path, String> displayByPath,
                               Set<Path> createdPaths,
                               Set<Path> deletedPaths) throws Exception {
        Path filePath = resolveWritablePath(op.filePath());
        if (createdPaths.contains(filePath)) throw new IllegalArgumentException(op.filePath() + ": cannot delete file added earlier in same patch");
        String current = currentContent(filePath, op.filePath(), contentByPath, originalContentByPath, displayByPath);
        contentByPath.put(filePath, null);
        deletedPaths.add(filePath);
    }

    private void prepareMove(V4aPatchParser.PatchOperation op,
                             Map<Path, String> contentByPath,
                             Map<Path, String> originalContentByPath,
                             Map<Path, String> displayByPath,
                             Set<Path> createdPaths,
                             Set<Path> deletedPaths) throws Exception {
        Path sourcePath = resolveWritablePath(op.filePath());
        Path targetPath = resolveWritablePath(op.newPath());
        if (workspace.exists(targetPath) || contentByPath.containsKey(targetPath)) {
            throw new IllegalArgumentException(op.newPath() + ": destination already exists");
        }
        String sourceContent = currentContent(sourcePath, op.filePath(), contentByPath, originalContentByPath, displayByPath);
        contentByPath.put(sourcePath, null);
        contentByPath.put(targetPath, sourceContent);
        originalContentByPath.put(targetPath, null);
        displayByPath.put(targetPath, op.newPath());
        createdPaths.add(targetPath);
        deletedPaths.add(sourcePath);
    }

    private Path resolveWritablePath(String path) throws Exception {
        Path filePath = FileSafety.resolve(workspace, path);
        String denied = FileSafety.checkWriteAllowed(workspace, path, filePath);
        if (denied != null) {
            throw new IllegalArgumentException(denied);
        }
        return filePath;
    }

    private String currentContent(Path filePath,
                                  String displayPath,
                                  Map<Path, String> contentByPath,
                                  Map<Path, String> originalContentByPath,
                                  Map<Path, String> displayByPath) throws Exception {
        if (contentByPath.containsKey(filePath)) {
            String content = contentByPath.get(filePath);
            if (content == null) throw new IllegalArgumentException(displayPath + ": file was already deleted in this patch");
            return content;
        }
        if (!workspace.exists(filePath)) throw new IllegalArgumentException(displayPath + ": file not found");
        String content = workspace.readString(filePath);
        contentByPath.put(filePath, content);
        originalContentByPath.put(filePath, content);
        displayByPath.put(filePath, displayPath);
        return content;
    }

    private String applyHunks(String content, V4aPatchParser.PatchOperation op) {
        String updated = content;
        for (V4aPatchParser.Hunk hunk : op.hunks()) {
            List<String> searchLines = hunk.lines().stream()
                .filter(l -> " ".equals(l.prefix()) || "-".equals(l.prefix()))
                .map(V4aPatchParser.HunkLine::content)
                .toList();
            List<String> replaceLines = hunk.lines().stream()
                .filter(l -> " ".equals(l.prefix()) || "+".equals(l.prefix()))
                .map(V4aPatchParser.HunkLine::content)
                .toList();

            if (searchLines.isEmpty()) {
                String insertText = String.join("\n", replaceLines);
                updated = insertAfterHint(updated, hunk, insertText);
            } else {
                FuzzyMatcher.MatchResult result = FuzzyMatcher.findAndReplaceStrict(
                        updated,
                        String.join("\n", searchLines),
                        String.join("\n", replaceLines),
                        false);
                if (result.error() != null) {
                    String label = hunk.contextHint().orElse("(no hint)");
                    throw new IllegalArgumentException(formatHunkNotFound(op.filePath(), label, searchLines, result.error()));
                }
                updated = result.newContent();
            }
        }
        return updated;
    }

    private String formatHunkNotFound(String filePath, String label, List<String> searchLines, String matcherError) {
        return filePath + ": hunk '" + label + "' not found. " + matcherError + "\n"
            + "Search content:\n"
            + formatSearchContent(searchLines) + "\n"
            + "Tip: Check if context lines match exactly, especially whitespace/empty lines.";
    }

    private String formatSearchContent(List<String> searchLines) {
        int limit = Math.min(searchLines.size(), 12);
        List<String> formatted = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String line = searchLines.get(i);
            formatted.add("  " + (line.isEmpty() ? "(empty line)" : line));
        }
        if (searchLines.size() > limit) {
            formatted.add("  ... (" + (searchLines.size() - limit) + " more lines)");
        }
        return String.join("\n", formatted);
    }

    private String insertAfterHint(String content, V4aPatchParser.Hunk hunk, String insertText) {
        if (hunk.contextHint().isEmpty()) {
            return content.stripTrailing() + "\n" + insertText + "\n";
        }
        String hint = hunk.contextHint().get();
        int occurrences = countOccurrences(content, hint);
        if (occurrences == 0) {
            return content.stripTrailing() + "\n" + insertText + "\n";
        }
        if (occurrences > 1) {
            throw new IllegalArgumentException("Addition-only hunk: context hint '" + hint + "' is ambiguous (" + occurrences + " occurrences)");
        }
        int hintPos = content.indexOf(hint);
        int eol = content.indexOf('\n', hintPos);
        if (eol == -1) return content + "\n" + insertText;
        return content.substring(0, eol + 1) + insertText + "\n" + content.substring(eol + 1);
    }

    private ToolResult commitChanges(List<PreparedChange> changes, String sessionId) throws Exception {
        Map<Path, String> rollbackContent = new LinkedHashMap<>();
        List<Path> createdDuringCommit = new ArrayList<>();
        List<String> warnings = collectWarnings(changes, sessionId);
        try {
            for (PreparedChange change : changes) {
                if (workspace.exists(change.path())) {
                    rollbackContent.put(change.path(), workspace.readString(change.path()));
                } else {
                    createdDuringCommit.add(change.path());
                }

                if (change.kind() == ChangeKind.DELETE) {
                    workspace.delete(change.path());
                } else {
                    Path parent = change.path().getParent();
                    if (parent != null && !workspace.exists(parent)) workspace.createDirectories(parent);
                    workspace.writeString(change.path(), change.afterContent());
                }
            }
            for (PreparedChange change : changes) {
                if (change.kind() != ChangeKind.DELETE) {
                    toolStateStore.markWritten(sessionId, FileSafety.canonicalForAccess(workspace, change.path()), workspace);
                }
            }
        } catch (Exception e) {
            rollback(rollbackContent, createdDuringCommit);
            return ToolResult.failure("Patch apply failed and rollback was attempted. Run `git diff` to verify state: " + e.getMessage());
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("files_modified", changes.stream()
                .filter(c -> c.kind() == ChangeKind.UPDATE)
                .map(PreparedChange::displayPath)
                .toList());
        output.put("files_created", changes.stream()
                .filter(c -> c.kind() == ChangeKind.ADD)
                .map(PreparedChange::displayPath)
                .toList());
        output.put("files_deleted", changes.stream()
                .filter(c -> c.kind() == ChangeKind.DELETE)
                .map(PreparedChange::displayPath)
                .toList());
        output.put("diff", changes.stream()
                .map(c -> FileUtils.generateDiff(
                        c.beforeContent() != null ? c.beforeContent() : "",
                        c.afterContent() != null ? c.afterContent() : "",
                        c.displayPath()))
                .filter(diff -> !diff.isBlank())
                .collect(Collectors.joining("\n")));
        if (!warnings.isEmpty()) {
            output.put("warning", String.join("\n", warnings));
        }
        return ToolResult.success(output);
    }

    private List<String> collectWarnings(List<PreparedChange> changes, String sessionId) throws Exception {
        if (sessionId == null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (PreparedChange change : changes) {
            if (change.beforeContent() == null || !workspace.exists(change.path())) {
                continue;
            }
            Path canonicalPath = FileSafety.canonicalForAccess(workspace, change.path());
            String warning = toolStateStore.checkBeforeWrite(sessionId, canonicalPath, workspace, true);
            if (warning != null && !warnings.contains(warning)) {
                warnings.add(warning);
                log.warn(warning);
            }
        }
        return warnings;
    }

    private void rollback(Map<Path, String> rollbackContent, List<Path> createdDuringCommit) {
        for (Path created : createdDuringCommit) {
            try {
                workspace.deleteIfExists(created);
            } catch (Exception e) {
                log.warn("Failed to rollback created file {}", created, e);
            }
        }
        for (Map.Entry<Path, String> entry : rollbackContent.entrySet()) {
            try {
                workspace.writeString(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("Failed to rollback file {}", entry.getKey(), e);
            }
        }
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += 1;
        }
        return count;
    }
}
