package io.github.huskyagent.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.*;

final class ToolCallDisplay {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESET = "\033[0m";
    private static final String BOLD  = "\033[1m";
    private static final String GRAY  = "\033[90m";
    private static final String GREEN = "\033[32m";
    private static final String RED   = "\033[31m";
    private static final String CYAN  = "\033[36m";
    private static final String YELLOW= "\033[33m";

    private static String lastStartedContent = null;
    private static final Map<String, String> startedLinesByCallId = new HashMap<>();


    private static final int SUB_AGENT_WINDOW = 5;

    private static class SubAgentToolPanel {
        final int taskIndex;
        final String goalPreview;
        int totalFinished = 0;
        String runningTool = null;
        final Deque<String> window = new ArrayDeque<>();
        boolean finished = false;

        SubAgentToolPanel(int taskIndex, String goalPreview) {
            this.taskIndex   = taskIndex;
            this.goalPreview = goalPreview;
        }
    }

    private static final LinkedHashMap<Integer, SubAgentToolPanel> subAgentPanels
            = new LinkedHashMap<>();

    private static int subAgentTotalRenderedLines = 0;

    /**
     * Buffers completed main-agent tool lines while sub-agent panels are on screen
     * so panel redraws do not interleave with the main tool timeline.
     */
    private static final List<String> pendingMainAgentLines = new ArrayList<>();

    private ToolCallDisplay() {}

    static void printStatus(String type, String toolName, String argsPreview,
                            long durationMs, String error,
                            PrintWriter out, Runnable flush) {
        printStatus(type, toolName, argsPreview, null, durationMs, error, null, out, flush);
    }

    static void printStatus(String type, String toolName, String argsPreview,
                            long durationMs, String error, String toolCallId,
                            PrintWriter out, Runnable flush) {
        printStatus(type, toolName, argsPreview, null, durationMs, error, toolCallId, out, flush);
    }

    static void printStatus(String type, String toolName, String argsPreview, String toolArgs,
                            long durationMs, String error, String toolCallId,
                            PrintWriter out, Runnable flush) {
        printStatus(new ToolStatusPayload(type, toolName, argsPreview, toolArgs, durationMs, error, toolCallId), out, flush);
    }

    static void printStatus(ToolStatusPayload status, PrintWriter out, Runnable flush) {
        boolean isStarted = "STARTED".equals(status.type());
        boolean isFailed  = "FAILED".equals(status.type());

        String preview = argsPreview(status.toolName(), status.toolArgs() != null && !status.toolArgs().isBlank() ? status.toolArgs() : status.argsPreview());

        synchronized (subAgentPanels) {
            if (!subAgentPanels.isEmpty()) {
                if (!isStarted) {
                    String icon = isFailed ? RED + "✗" + RESET : GREEN + "✓" + RESET;
                    String duration = String.format("%.1fs", status.durationMs() / 1000.0);
                    StringBuilder line = new StringBuilder();
                    line.append("  ").append(icon).append(" ");
                    line.append(BOLD).append(padRight(status.toolName(), 16)).append(RESET).append(" ");
                    line.append(GRAY).append(preview).append(RESET);
                    line.append("  ").append(GRAY).append(duration).append(RESET);
                    if (isFailed && status.error() != null) {
                        String errMsg = status.error().length() > 80 ? status.error().substring(0, 77) + "..." : status.error();
                        line.append("  ").append(RED).append(errMsg).append(RESET);
                    }
                    pendingMainAgentLines.add(line.toString());
                }
                if (status.toolCallId() != null && !status.toolCallId().isBlank()) {
                    startedLinesByCallId.remove(status.toolCallId());
                }
                lastStartedContent = null;
                return;
            }
        }

        printStatusLine(isStarted, isFailed, status.toolName(), preview, status.durationMs(), status.error(), status.toolCallId(), out);
        flush.run();
    }

    private static void printStatusLine(boolean isStarted, boolean isFailed,
                                        String toolName, String preview,
                                        long durationMs, String error, String toolCallId,
                                        PrintWriter out) {
        if (isStarted) {
            String line = "  ⏳ " + BOLD + padRight(toolName, 16) + RESET + " " + GRAY + preview + RESET;
            if (toolCallId != null && !toolCallId.isBlank()) {
                startedLinesByCallId.put(toolCallId, line);
                lastStartedContent = null;
            } else {
                lastStartedContent = line;
            }
            out.println(line);
        } else {
            String icon = isFailed ? RED + "✗" + RESET : GREEN + "✓" + RESET;
            String duration = String.format("%.1fs", durationMs / 1000.0);

            StringBuilder line = new StringBuilder();
            line.append("  ").append(icon).append(" ");
            line.append(BOLD).append(padRight(toolName, 16)).append(RESET).append(" ");
            line.append(GRAY).append(preview).append(RESET);
            line.append("  ").append(GRAY).append(duration).append(RESET);
            if (isFailed && error != null) {
                String errMsg = error.length() > 80 ? error.substring(0, 77) + "..." : error;
                line.append("  ").append(RED).append(errMsg).append(RESET);
            }

            String newLine = line.toString();
            String startedLine = null;
            boolean canOverwrite = false;
            if (toolCallId != null && !toolCallId.isBlank()) {
                startedLinesByCallId.remove(toolCallId);
            } else if (lastStartedContent != null) {
                startedLine = lastStartedContent;
                lastStartedContent = null;
                canOverwrite = true;
            }

            if (canOverwrite && startedLine != null) {
                int newLen = stripAnsi(newLine).length();
                int oldLen = stripAnsi(startedLine).length();
                int padding = Math.max(0, oldLen - newLen);
                out.print("\033[A\r" + newLine + " ".repeat(padding) + "\n");
            } else {
                out.println(newLine);
            }
        }
    }


    static void printTodoPanel(JsonNode items, PrintWriter out, Runnable flush) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            return;
        }

        int width = 42;
        for (JsonNode item : items) {
            String content = item.has("content") ? item.get("content").asText("") : "";
            int len = content.length() + 8;
            width = Math.max(width, Math.min(len, 60));
        }

        String top = "┌─ Todo " + "─".repeat(Math.max(1, width - 8)) + "┐";
        out.println(GRAY + "  " + top + RESET);

        int pending = 0, inProgress = 0, completed = 0, cancelled = 0;

        for (JsonNode item : items) {
            String id = item.has("id") ? item.get("id").asText("") : "";
            String content = item.has("content") ? item.get("content").asText("") : "";
            String status = item.has("status") ? item.get("status").asText("pending") : "pending";

            String marker = switch (status) {
                case "in_progress" -> YELLOW + "[>]" + RESET;
                case "completed" -> GREEN + "[x]" + RESET;
                case "cancelled" -> RED + "[~]" + RESET;
                default -> GRAY + "[ ]" + RESET;
            };
            String contentColor = switch (status) {
                case "completed", "cancelled" -> GRAY;
                default -> RESET;
            };
            switch (status) {
                case "in_progress" -> inProgress++;
                case "completed" -> completed++;
                case "cancelled" -> cancelled++;
                default -> pending++;
            }
            String line = marker + " " + BOLD + id + "." + RESET + " " + contentColor + content + RESET;
            out.println(GRAY + "  │ " + RESET + line);
        }

        String bottom = "└" + "─".repeat(width + 1) + "┘";
        out.println(GRAY + "  " + bottom + RESET);

        List<String> parts = new ArrayList<>();
        if (completed > 0) parts.add(GREEN + completed + " completed" + RESET);
        if (inProgress > 0) parts.add(YELLOW + inProgress + " in progress" + RESET);
        if (pending > 0) parts.add(pending + " pending");
        if (cancelled > 0) parts.add(RED + cancelled + " cancelled" + RESET);

        if (!parts.isEmpty()) {
            out.println(GRAY + "    " + String.join(GRAY + " · " + RESET, parts) + RESET);
        }

        flush.run();
    }


    static void printSubAgentStart(int taskIndex, String goal,
                                   PrintWriter out, Runnable flush) {
        String goalPreview = singleLinePreview(goal, 60);
        synchronized (subAgentPanels) {
            subAgentPanels.put(taskIndex, new SubAgentToolPanel(taskIndex, goalPreview));
            renderAllPanels(out, flush);
        }
    }

    static void printSubAgentToolEvent(int taskIndex, String type, String toolName,
                                       String argsPreview, long durationMs, String error,
                                       PrintWriter out, Runnable flush) {
        synchronized (subAgentPanels) {
            SubAgentToolPanel panel = subAgentPanels.get(taskIndex);
            if (panel == null || panel.finished) return;

            boolean isStarted   = "STARTED".equals(type);
            boolean isFailed    = "FAILED".equals(type);
            boolean isCompleted = "COMPLETED".equals(type);
            String preview = argsPreview(toolName, argsPreview);

            if (isStarted) {
                panel.runningTool = toolName;
            } else if (isCompleted || isFailed) {
                panel.totalFinished++;
                panel.runningTool = null;

                String icon = isFailed ? RED + "✗" + RESET : GREEN + "✓" + RESET;
                String duration = String.format("%.1fs", durationMs / 1000.0);
                StringBuilder line = new StringBuilder();
                line.append(icon).append(" ");
                line.append(BOLD).append(padRight(toolName, 16)).append(RESET).append(" ");
                line.append(GRAY).append(preview).append(RESET);
                line.append("  ").append(GRAY).append(duration).append(RESET);
                if (isFailed && error != null) {
                    String errMsg = error.length() > 60 ? error.substring(0, 57) + "..." : error;
                    line.append("  ").append(RED).append(errMsg).append(RESET);
                }
                panel.window.addLast(line.toString());
                if (panel.window.size() > SUB_AGENT_WINDOW) {
                    panel.window.pollFirst();
                }
            }

            renderAllPanels(out, flush);
        }
    }

    static void printSubAgentEnd(int taskIndex, String status, long durationMs,
                                 String summary,
                                 PrintWriter out, Runnable flush) {
        String icon = switch (status) {
            case "completed" -> GREEN + "✓" + RESET;
            case "failed"    -> RED   + "✗" + RESET;
            case "timeout"   -> YELLOW + "⏱" + RESET;
            default          -> "·";
        };
        String duration = String.format("%.1fs", durationMs / 1000.0);
        String summaryPreview = singleLinePreview(summary, 60);

        synchronized (subAgentPanels) {
            SubAgentToolPanel panel = subAgentPanels.get(taskIndex);
            if (panel != null) {
                panel.finished = true;
                panel.runningTool = null;
                panel.window.clear();
                panel.window.addLast(icon + " " + BOLD + "Completed" + RESET
                        + "  " + GRAY + duration + RESET
                        + (summaryPreview.isBlank() ? "" : "  " + GRAY + summaryPreview + RESET));
            }

            renderAllPanels(out, flush);

            boolean allDone = subAgentPanels.values().stream().allMatch(p -> p.finished);
            if (allDone) {
                subAgentPanels.clear();
                subAgentTotalRenderedLines = 0;
                if (!pendingMainAgentLines.isEmpty()) {
                    for (String line : pendingMainAgentLines) {
                        out.println(line);
                    }
                    pendingMainAgentLines.clear();
                    flush.run();
                }
            }
        }
    }

    private static void renderAllPanels(PrintWriter out, Runnable flush) {
        if (subAgentTotalRenderedLines > 0) {
            out.print("\033[" + subAgentTotalRenderedLines + "A");
        }

        List<String> allLines = new ArrayList<>();
        for (SubAgentToolPanel panel : subAgentPanels.values()) {
            buildPanelLines(panel, allLines);
        }

        for (String l : allLines) {
            out.print("\r\033[K");
            out.println(l);
        }
        for (int i = allLines.size(); i < subAgentTotalRenderedLines; i++) {
            out.print("\r\033[K\n");
        }
        if (subAgentTotalRenderedLines > allLines.size()) {
            out.print("\033[" + (subAgentTotalRenderedLines - allLines.size()) + "A");
        }

        subAgentTotalRenderedLines = allLines.size();
        flush.run();
    }

    private static void buildPanelLines(SubAgentToolPanel panel, List<String> lines) {
        lines.add(CYAN + "┌ ⚡ Sub-agent #" + (panel.taskIndex + 1)
                + BOLD + " " + panel.goalPreview + RESET);

        if (panel.finished) {
            String endContent = panel.window.isEmpty() ? "" : panel.window.peekFirst();
            lines.add(CYAN + "└ " + RESET + endContent);
        } else {
            for (String entry : panel.window) {
                lines.add(CYAN + "│" + RESET + " " + entry);
            }
            if (panel.runningTool != null) {
                lines.add(CYAN + "│" + RESET + " ⏳ " + BOLD
                        + padRight(panel.runningTool, 16) + RESET);
            }
            if (!panel.window.isEmpty() || panel.runningTool != null || panel.totalFinished > 0) {
                int total = panel.totalFinished + (panel.runningTool != null ? 1 : 0);
                String hiddenHint = panel.totalFinished > SUB_AGENT_WINDOW
                        ? GRAY + "  (showing only the latest " + SUB_AGENT_WINDOW + " entries)" + RESET : "";
                lines.add(CYAN + "│" + RESET + GRAY + " called " + RESET
                        + BOLD + total + RESET + GRAY + " tools" + RESET + hiddenHint);
            }
        }
    }

    static String emoji(String name) {
        if (name == null) return "🔧";
        return switch (name) {
            case "read_file"                            -> "📄";
            case "write_file"                           -> "✍️ ";
            case "edit_file", "apply_patch"             -> "✏️ ";
            case "delete_file"                          -> "🗑️ ";
            case "list_files"                           -> "📂";
            case "search_files"                         -> "🔍";
            case "move_file"                            -> "📦";
            case "terminal"                             -> "💻";
            case "process"                              -> "⚙️ ";
            case "web_search"                           -> "🌐";
            case "web_fetch"                            -> "📡";
            case "memory_read", "memory_write",
                 "memory_append"                        -> "🧠";
            case "session_search"                       -> "💾";
            case "todo"                                 -> "📋";
            default                                     -> "🔧";
        };
    }

    static String argsPreview(String name, String argsPreview) {
        if (argsPreview == null || argsPreview.isBlank()) return "";
        try {
            JsonNode node = MAPPER.readTree(argsPreview);
            if (!node.isObject()) {
                return truncate(node.asText(argsPreview), 150);
            }
            List<String> parts = new ArrayList<>();
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (value == null || value.isNull()) return;
                parts.add(key + "=" + previewValue(key, value));
            });
            return truncate(String.join(" ", parts), 150);
        } catch (Exception e) {
            return truncate(argsPreview, 150);
        }
    }

    private static String previewValue(String key, JsonNode value) {
        if (value.isTextual()) {
            String text = value.asText();
            return text.contains(" ") ? "\"" + truncate(text, 80) + "\"" : truncate(text, 80);
        }
        if (value.isBoolean() || value.isNumber()) {
            return value.asText();
        }
        if (value.isArray()) {
            return "<" + value.size() + " items>";
        }
        if (value.isObject()) {
            return "<object>";
        }
        return truncate(value.toString(), 80);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, Math.max(0, max - 3)) + "..." : value;
    }

    /**
     * Collapses multi-line markdown or summaries into a bounded single-line preview
     * suitable for status panels.
     */
    private static String singleLinePreview(String value, int max) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String collapsed = value
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return truncate(collapsed, max);
    }

    static String padRight(String s, int width) {
        if (s == null) s = "";
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    record ToolStatusPayload(String type,
                             String toolName,
                             String argsPreview,
                             String toolArgs,
                             long durationMs,
                             String error,
                             String toolCallId) {
    }

    private static String stripAnsi(String s) {
        if (s == null) return "";
        return s.replaceAll("\033\\[[0-9;]*m", "");
    }


    static void printToolStarted(Terminal terminal, JsonNode payload) {
        String toolName = payload.has("toolName") ? payload.get("toolName").asText() : "unknown";
        String argsPreview = payload.has("argsPreview") ? payload.get("argsPreview").asText() : "";
        String toolArgs = payload.has("toolArgs") ? payload.get("toolArgs").asText() : null;
        String toolCallId = payload.has("toolCallId") ? payload.get("toolCallId").asText() : null;
        printStatus("STARTED", toolName, argsPreview, toolArgs, 0, null, toolCallId,
                terminal.writer(), terminal::flush);
    }

    static void printToolCompleted(Terminal terminal, JsonNode payload) {
        String toolName = payload.has("toolName") ? payload.get("toolName").asText() : "unknown";
        String argsPreview = payload.has("argsPreview") ? payload.get("argsPreview").asText() : "";
        String toolArgs = payload.has("toolArgs") ? payload.get("toolArgs").asText() : null;
        String toolCallId = payload.has("toolCallId") ? payload.get("toolCallId").asText() : null;
        long durationMs = payload.has("durationMs") ? payload.get("durationMs").asLong() : 0;
        printStatus("COMPLETED", toolName, argsPreview, toolArgs, durationMs, null, toolCallId,
                terminal.writer(), terminal::flush);
    }

    static void printToolFailed(Terminal terminal, JsonNode payload) {
        String toolName = payload.has("toolName") ? payload.get("toolName").asText() : "unknown";
        String argsPreview = payload.has("argsPreview") ? payload.get("argsPreview").asText() : "";
        String toolArgs = payload.has("toolArgs") ? payload.get("toolArgs").asText() : null;
        String toolCallId = payload.has("toolCallId") ? payload.get("toolCallId").asText() : null;
        long durationMs = payload.has("durationMs") ? payload.get("durationMs").asLong() : 0;
        String error = payload.has("error") ? payload.get("error").asText() : null;
        printStatus("FAILED", toolName, argsPreview, toolArgs, durationMs, error, toolCallId,
                terminal.writer(), terminal::flush);
    }
}