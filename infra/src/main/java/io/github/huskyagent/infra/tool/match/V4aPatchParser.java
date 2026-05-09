package io.github.huskyagent.infra.tool.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for V4A patch format (used by codex, cline, and other coding agents).
 *
 * V4A Format:
 *   *** Begin Patch
 *   *** Update File: path/to/file.py
 *   @@ optional context hint @@
 *    context line (space prefix)
 *   -removed line (minus prefix)
 *   +added line (plus prefix)
 *   *** Add File: path/to/new.py
 *   +new file content
 *   *** Delete File: path/to/old.py
 *   *** Move File: old/path.py -> new/path.py
 *   *** End Patch
 */
public class V4aPatchParser {

    public enum OperationType {
        ADD, UPDATE, DELETE, MOVE
    }

    public record HunkLine(String prefix, String content) {}

    public record Hunk(Optional<String> contextHint, List<HunkLine> lines) {}

    public record PatchOperation(
        OperationType operation,
        String filePath,
        String newPath,
        List<Hunk> hunks
    ) {}

    public record ParseResult(List<PatchOperation> operations, String error) {}

    private static final Pattern UPDATE_RE = Pattern.compile("\\*\\*\\*\\s*Update\\s+File:\\s*(.+)");
    private static final Pattern ADD_RE = Pattern.compile("\\*\\*\\*\\s*Add\\s+File:\\s*(.+)");
    private static final Pattern DELETE_RE = Pattern.compile("\\*\\*\\*\\s*Delete\\s+File:\\s*(.+)");
    private static final Pattern MOVE_RE = Pattern.compile("\\*\\*\\*\\s*Move\\s*File:\\s*(.+?)\\s*->\\s*(.+)");
    private static final Pattern HINT_RE = Pattern.compile("@@\\s*(.+?)\\s*@@");

    public static ParseResult parse(String patchContent) {
        String[] lines = patchContent.split("\n", -1);

        int startIdx = -1;
        int endIdx = -1;

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals("*** Begin Patch")) {
                startIdx = i;
            } else if (lines[i].equals("*** End Patch")) {
                endIdx = i;
                break;
            }
        }

        if (startIdx == -1 || endIdx == -1 || startIdx >= endIdx) {
            return new ParseResult(List.of(), "Patch must start with '*** Begin Patch' and end with '*** End Patch'");
        }

        List<PatchOperation> operations = new ArrayList<>();
        PatchOperation currentOp = null;
        Hunk currentHunk = null;

        int i = startIdx + 1;
        while (i < endIdx) {
            String line = lines[i];

            Matcher updateMatch = UPDATE_RE.matcher(line);
            Matcher addMatch = ADD_RE.matcher(line);
            Matcher deleteMatch = DELETE_RE.matcher(line);
            Matcher moveMatch = MOVE_RE.matcher(line);

            if (updateMatch.find()) {
                if (currentOp != null) {
                    if (currentHunk != null && !currentHunk.lines().isEmpty()) {
                        currentOp = new PatchOperation(currentOp.operation(), currentOp.filePath(),
                            currentOp.newPath(), appendHunk(currentOp.hunks(), currentHunk));
                    }
                    operations.add(currentOp);
                }
                currentOp = new PatchOperation(OperationType.UPDATE, updateMatch.group(1).strip(), null, new ArrayList<>());
                currentHunk = null;
            } else if (addMatch.find()) {
                if (currentOp != null) {
                    if (currentHunk != null && !currentHunk.lines().isEmpty()) {
                        currentOp = new PatchOperation(currentOp.operation(), currentOp.filePath(),
                            currentOp.newPath(), appendHunk(currentOp.hunks(), currentHunk));
                    }
                    operations.add(currentOp);
                }
                currentOp = new PatchOperation(OperationType.ADD, addMatch.group(1).strip(), null, new ArrayList<>());
                currentHunk = new Hunk(Optional.empty(), new ArrayList<>());
            } else if (deleteMatch.find()) {
                if (currentOp != null) {
                    if (currentHunk != null && !currentHunk.lines().isEmpty()) {
                        currentOp = new PatchOperation(currentOp.operation(), currentOp.filePath(),
                            currentOp.newPath(), appendHunk(currentOp.hunks(), currentHunk));
                    }
                    operations.add(currentOp);
                }
                currentOp = new PatchOperation(OperationType.DELETE, deleteMatch.group(1).strip(), null, new ArrayList<>());
                operations.add(currentOp);
                currentOp = null;
                currentHunk = null;
            } else if (moveMatch.find()) {
                if (currentOp != null) {
                    if (currentHunk != null && !currentHunk.lines().isEmpty()) {
                        currentOp = new PatchOperation(currentOp.operation(), currentOp.filePath(),
                            currentOp.newPath(), appendHunk(currentOp.hunks(), currentHunk));
                    }
                    operations.add(currentOp);
                }
                currentOp = new PatchOperation(OperationType.MOVE, moveMatch.group(1).strip(),
                    moveMatch.group(2).strip(), new ArrayList<>());
                operations.add(currentOp);
                currentOp = null;
                currentHunk = null;
            } else if (line.startsWith("@@")) {
                if (currentOp != null) {
                    if (currentHunk != null && !currentHunk.lines().isEmpty()) {
                        currentOp = new PatchOperation(currentOp.operation(), currentOp.filePath(),
                            currentOp.newPath(), appendHunk(currentOp.hunks(), currentHunk));
                    }
                    Matcher hintMatch = HINT_RE.matcher(line);
                    String hint = hintMatch.find() ? hintMatch.group(1) : null;
                    currentHunk = new Hunk(Optional.ofNullable(hint), new ArrayList<>());
                }
            } else if (currentOp != null) {
                if (currentHunk == null) currentHunk = new Hunk(Optional.empty(), new ArrayList<>());

                if (line.startsWith("+")) {
                    currentHunk = new Hunk(currentHunk.contextHint(),
                        appendLine(currentHunk.lines(), new HunkLine("+", line.substring(1))));
                } else if (line.startsWith("-")) {
                    currentHunk = new Hunk(currentHunk.contextHint(),
                        appendLine(currentHunk.lines(), new HunkLine("-", line.substring(1))));
                } else if (line.startsWith(" ")) {
                    currentHunk = new Hunk(currentHunk.contextHint(),
                        appendLine(currentHunk.lines(), new HunkLine(" ", line.substring(1))));
                } else if (line.isEmpty()) {
                    currentHunk = new Hunk(currentHunk.contextHint(),
                        appendLine(currentHunk.lines(), new HunkLine(" ", "")));
                } else if (line.startsWith("\\")) {
                    // "\ No newline at end of file" — skip
                } else {
                    currentHunk = new Hunk(currentHunk.contextHint(),
                        appendLine(currentHunk.lines(), new HunkLine(" ", line)));
                }
            }

            i++;
        }

        // Don't forget the last operation
        if (currentOp != null) {
            if (currentHunk != null && !currentHunk.lines().isEmpty()) {
                currentOp = new PatchOperation(currentOp.operation(), currentOp.filePath(),
                    currentOp.newPath(), appendHunk(currentOp.hunks(), currentHunk));
            }
            operations.add(currentOp);
        }

        // Validate
        List<String> parseErrors = new ArrayList<>();
        for (PatchOperation op : operations) {
            if (op.filePath() == null || op.filePath().isEmpty()) {
                parseErrors.add("Operation with empty file path");
            }
            if (op.operation() == OperationType.UPDATE && op.hunks().isEmpty()) {
                parseErrors.add("UPDATE '" + op.filePath() + "': no hunks found");
            }
            if (op.operation() == OperationType.MOVE && op.newPath() == null) {
                parseErrors.add("MOVE '" + op.filePath() + "': missing destination path");
            }
        }

        if (!parseErrors.isEmpty()) {
            return new ParseResult(List.of(), "Parse error: " + String.join("; ", parseErrors));
        }

        return new ParseResult(operations, null);
    }

    private static List<Hunk> appendHunk(List<Hunk> hunks, Hunk hunk) {
        List<Hunk> newHunks = new ArrayList<>(hunks);
        newHunks.add(hunk);
        return newHunks;
    }

    private static List<HunkLine> appendLine(List<HunkLine> lines, HunkLine line) {
        List<HunkLine> newLines = new ArrayList<>(lines);
        newLines.add(line);
        return newLines;
    }
}