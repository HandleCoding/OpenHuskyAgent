package io.github.huskyagent.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.*;

/**
 * 工具调用列表渲染工具：emoji 映射、参数预览提取、状态行原地更新。
 * （client-side version — 无 AssistantMessage/TodoStore 依赖）
 */
final class ToolCallDisplay {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESET = "\033[0m";
    private static final String BOLD  = "\033[1m";
    private static final String GRAY  = "\033[90m";
    private static final String GREEN = "\033[32m";
    private static final String RED   = "\033[31m";
    private static final String CYAN  = "\033[36m";
    private static final String YELLOW= "\033[33m";

    /** 最近一次 STARTED 行的内容（串行 fallback），用于 COMPLETED 时原地覆盖 */
    private static String lastStartedContent = null;
    /** 并发工具调用按 toolCallId 记录 started 行，避免完成事件覆盖到别的调用 */
    private static final Map<String, String> startedLinesByCallId = new HashMap<>();

    // ── 子 Agent 并发面板渲染 ─────────────────────────────────────────────────

    private static final int SUB_AGENT_WINDOW = 5;

    /**
     * 单个子 Agent 的状态。
     */
    private static class SubAgentToolPanel {
        final int taskIndex;
        final String goalPreview;
        /** 已完成（或失败）的工具总数 */
        int totalFinished = 0;
        /** 正在执行中的工具名 */
        String runningTool = null;
        /** 滑动窗口：最多 SUB_AGENT_WINDOW 条已完成行（不含边框前缀） */
        final Deque<String> window = new ArrayDeque<>();
        /** 是否已结束（completed/failed/timeout） */
        boolean finished = false;

        SubAgentToolPanel(int taskIndex, String goalPreview) {
            this.taskIndex   = taskIndex;
            this.goalPreview = goalPreview;
        }
    }

    /** taskIndex → SubAgentToolPanel，按插入顺序排列 */
    private static final LinkedHashMap<Integer, SubAgentToolPanel> subAgentPanels
            = new LinkedHashMap<>();

    /**
     * 所有活跃面板上次共渲染的总行数（用于整体上移）。
     * 必须在持有 subAgentPanels 锁时读写。
     */
    private static int subAgentTotalRenderedLines = 0;

    /**
     * 面板活跃期间主 Agent 工具行的缓冲区。
     * 面板消失后一次性 flush，避免主 Agent 行与面板块交错导致重绘错乱。
     * 必须在持有 subAgentPanels 锁时读写。
     */
    private static final List<String> pendingMainAgentLines = new ArrayList<>();

    private ToolCallDisplay() {}

    /**
     * 打印工具执行状态行，支持原地更新：
     * - STARTED：打印 ⏳ 行
     * - COMPLETED/FAILED：如果上一行是 STARTED 且同工具，原地覆盖；否则新打印一行
     */
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
                // 面板活跃期间：把主 Agent 行缓冲起来，等面板全部结束后统一输出
                // 不做原地覆盖（STARTED → COMPLETED 合并），直接以完成态形式缓冲
                if (!isStarted) {
                    // 只缓冲完成/失败行，跳过 STARTED（完成态已含工具名和时间，足够）
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
                // 清除 STARTED 记录（不会被用到）
                if (status.toolCallId() != null && !status.toolCallId().isBlank()) {
                    startedLinesByCallId.remove(status.toolCallId());
                }
                lastStartedContent = null;
                return; // 暂不输出到终端
            }
        }

        // 没有活跃面板，正常打印（允许原地覆盖）
        printStatusLine(isStarted, isFailed, status.toolName(), preview, status.durationMs(), status.error(), status.toolCallId(), out);
        flush.run();
    }

    /** 打印单条主 Agent 工具状态行，支持 STARTED→COMPLETED 原地覆盖。 */
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

    // ── Todo 列表渲染 ──────────────────────────────────────────────────────────

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

    // ── 子 Agent 视觉边界 ──────────────────────────────────────────────────────

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
                panel.window.addLast(icon + " " + BOLD + "完成" + RESET
                        + "  " + GRAY + duration + RESET
                        + (summaryPreview.isBlank() ? "" : "  " + GRAY + summaryPreview + RESET));
            }

            // 整体重绘（把最新的 └ 行渲染到位）
            renderAllPanels(out, flush);

            // 所有面板都结束后：清理并冻结，让光标留在块末尾，主 Agent 输出从这里继续
            boolean allDone = subAgentPanels.values().stream().allMatch(p -> p.finished);
            if (allDone) {
                subAgentPanels.clear();
                subAgentTotalRenderedLines = 0; // 块已"固化"，不再上移
                // 输出面板活跃期间缓冲的主 Agent 行
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
        // 上移到块顶部
        if (subAgentTotalRenderedLines > 0) {
            out.print("\033[" + subAgentTotalRenderedLines + "A");
        }

        List<String> allLines = new ArrayList<>();
        for (SubAgentToolPanel panel : subAgentPanels.values()) {
            buildPanelLines(panel, allLines);
        }

        for (String l : allLines) {
            out.print("\r\033[K"); // 清除当前行残留内容
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
        // ┌ 标题行
        lines.add(CYAN + "┌ ⚡ 子Agent #" + (panel.taskIndex + 1)
                + BOLD + " " + panel.goalPreview + RESET);

        if (panel.finished) {
            // 结束行：window 里存的就是已格式化的结束内容
            String endContent = panel.window.isEmpty() ? "" : panel.window.peekFirst();
            lines.add(CYAN + "└ " + RESET + endContent);
        } else {
            // 已完成工具（滑动窗口）
            for (String entry : panel.window) {
                lines.add(CYAN + "│" + RESET + " " + entry);
            }
            // 当前正在执行
            if (panel.runningTool != null) {
                lines.add(CYAN + "│" + RESET + " ⏳ " + BOLD
                        + padRight(panel.runningTool, 16) + RESET);
            }
            // 计数行
            if (!panel.window.isEmpty() || panel.runningTool != null || panel.totalFinished > 0) {
                int total = panel.totalFinished + (panel.runningTool != null ? 1 : 0);
                String hiddenHint = panel.totalFinished > SUB_AGENT_WINDOW
                        ? GRAY + "  (仅显示最近 " + SUB_AGENT_WINDOW + " 条)" + RESET : "";
                lines.add(CYAN + "│" + RESET + GRAY + " 已调用 " + RESET
                        + BOLD + total + RESET + GRAY + " 个工具" + RESET + hiddenHint);
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

    // ── JSON-RPC 事件便捷方法 ──────────────────────────────────────────────────

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