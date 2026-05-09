package io.github.huskyagent.tui;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.huskyagent.rpc.JsonRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * TUI 命令处理器：通过 JSON-RPC 客户端实现 /xxx 命令。
 */
@Slf4j
class CommandHandler {

    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String GREEN  = "\033[32m";
    private static final String BLUE   = "\033[34m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN   = "\033[36m";
    private static final String GRAY   = "\033[90m";
    private static final String RED    = "\033[31m";

    private final JsonRpcClient client;
    private final Terminal      terminal;
    private final LineReader    reader;
    private final AgentCompleter completer;

    private String currentSessionId;
    private Path   workingDirectory;

    private boolean exitRequested = false;

    CommandHandler(JsonRpcClient client, Terminal terminal, LineReader reader,
                   AgentCompleter completer, String currentSessionId, Path workingDirectory) {
        this.client          = client;
        this.terminal        = terminal;
        this.reader          = reader;
        this.completer       = completer;
        this.currentSessionId = currentSessionId;
        this.workingDirectory = workingDirectory;
    }

    // ── 公开接口 ─────────────────────────────────────────────────────────────────

    void handle(String command) {
        String[] parts = command.split("\\s+", 2);
        String cmd  = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/help"                 -> printHelp();
            case "/exit", "/quit", "/q" -> { println(BLUE + "再见！👋" + RESET); exitRequested = true; }
            case "/new"                  -> createNewSession();
            case "/resume"               -> resumeSession(args);
            case "/rewind"               -> rewindSession();
            case "/session"              -> showSessionInfo();
            case "/sessions"             -> listSessions();
            case "/clear"                -> clearScreen();
            case "/cd"                   -> changeDirectory(args);
            case "/pwd"                  -> showWorkingDirectory();
            case "/status"               -> showContextStatus();
            case "/memory"               -> showMemory();
            default -> println(RED + "❌ 未知命令: " + cmd + "，输入 /help 查看帮助" + RESET);
        }
    }

    boolean isExitRequested()    { return exitRequested; }
    String  getCurrentSessionId(){ return currentSessionId; }
    Path    getWorkingDirectory() { return workingDirectory; }

    // ── 命令实现 ─────────────────────────────────────────────────────────────────

    private void printHelp() {
        println("");
        println(BOLD + "命令列表:" + RESET);
        println("  " + YELLOW + "/help" + RESET + "      - 显示帮助信息");
        println("  " + YELLOW + "/new" + RESET + "       - 创建新会话");
        println("  " + YELLOW + "/resume" + RESET + "    - 恢复历史会话（交互选择）");
        println("  " + YELLOW + "/rewind" + RESET + "    - 回退到某条历史消息（撤销对话）");
        println("  " + YELLOW + "/session" + RESET + "   - 显示当前会话信息");
        println("  " + YELLOW + "/sessions" + RESET + "  - 列出所有会话");
        println("  " + YELLOW + "/status" + RESET + "    - 显示上下文状态");
        println("  " + YELLOW + "/memory" + RESET + "    - 查看记忆系统");
        println("  " + YELLOW + "/cd <dir>" + RESET + "  - 切换工作目录");
        println("  " + YELLOW + "/pwd" + RESET + "       - 显示当前目录");
        println("  " + YELLOW + "/clear" + RESET + "     - 清屏");
        println("  " + YELLOW + "/exit" + RESET + "      - 退出程序");
        println("");
    }

    private void createNewSession() {
        try {
            JsonNode result = client.request("session.create", Map.of()).get();
            if (result != null && result.has("sessionId")) {
                currentSessionId = result.get("sessionId").asText();
                println(GREEN + "✓ 已创建新会话 " + CYAN + currentSessionId.substring(0, 8) + RESET);
            }
        } catch (Exception e) {
            println(RED + "❌ 创建会话失败: " + e.getMessage() + RESET);
        }
    }

    private void resumeSession(String args) {
        try {
            JsonNode result = client.request("session.list", Map.of()).get();
            if (result == null || !result.has("sessions")) {
                println(GRAY + "无法获取会话列表" + RESET);
                return;
            }
            JsonNode sessions = result.get("sessions");
            if (sessions.isEmpty()) {
                println(GRAY + "没有历史会话" + RESET);
                return;
            }

            // 若 args 直接指定了 sessionId 前缀，尝试匹配
            if (!args.isBlank()) {
                for (JsonNode s : sessions) {
                    String id = s.has("id") ? s.get("id").asText() : "";
                    if (id.startsWith(args)) {
                        doSwitch(id);
                        return;
                    }
                }
                println(RED + "❌ 未找到匹配的会话: " + args + RESET);
                return;
            }

            // 交互式选择：列出会话供用户输入编号
            println("");
            println(BOLD + "历史会话:" + RESET);
            List<String> ids = new ArrayList<>();
            int idx = 1;
            for (JsonNode s : sessions) {
                String id   = s.has("id")        ? s.get("id").asText()        : "?";
                String updAt = s.has("updatedAt") ? s.get("updatedAt").asText() : "";
                String msgs  = s.has("messageCount") ? s.get("messageCount").asText() : "";
                String marker = id.equals(currentSessionId) ? YELLOW + " ← 当前" + RESET : "";
                String info = updAt.isBlank() ? "" : GRAY + "  " + updAt.substring(0, Math.min(16, updAt.length())) + RESET;
                println("  " + CYAN + "[" + idx + "]" + RESET + " " + id.substring(0, Math.min(8, id.length())) + info + marker);
                ids.add(id);
                idx++;
            }
            println("");

            String input;
            try {
                input = reader.readLine(BOLD + "选择会话编号 (回车取消): " + RESET);
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                return;
            }
            if (input == null || input.isBlank()) return;

            int chosen;
            try {
                chosen = Integer.parseInt(input.trim());
            } catch (NumberFormatException e) {
                println(RED + "❌ 请输入有效的编号" + RESET);
                return;
            }
            if (chosen < 1 || chosen > ids.size()) {
                println(RED + "❌ 编号超出范围" + RESET);
                return;
            }
            doSwitch(ids.get(chosen - 1));

        } catch (Exception e) {
            println(RED + "❌ 获取会话列表失败: " + e.getMessage() + RESET);
        }
    }

    private void doSwitch(String sessionId) {
        try {
            JsonNode result = client.request("session.switch", Map.of("sessionId", sessionId)).get();
            if (result != null && result.has("sessionId")) {
                currentSessionId = result.get("sessionId").asText();
                println(GREEN + "✓ 已切换到会话 " + CYAN + currentSessionId.substring(0, 8) + RESET);
            } else {
                println(RED + "❌ 切换失败" + RESET);
            }
        } catch (Exception e) {
            println(RED + "❌ 切换会话失败: " + e.getMessage() + RESET);
        }
    }

    private void rewindSession() {
        try {
            JsonNode result = client.request("session.user-messages", Map.of()).get();
            if (result == null || !result.has("messages")) {
                println(GRAY + "无法获取消息列表" + RESET);
                return;
            }
            JsonNode messages = result.get("messages");
            if (messages.isEmpty()) {
                println(GRAY + "当前会话没有消息记录" + RESET);
                return;
            }

            // 展示用户消息列表
            println("");
            println(BOLD + "选择回退到哪条消息（该消息之后的内容将被删除）:" + RESET);
            List<Long> ids = new ArrayList<>();
            int idx = 1;
            for (JsonNode msg : messages) {
                long msgId   = msg.has("id")        ? msg.get("id").asLong()        : -1;
                String content  = msg.has("content")   ? msg.get("content").asText()   : "";
                String createdAt = msg.has("createdAt") ? msg.get("createdAt").asText() : "";
                String preview  = content.length() > 60 ? content.substring(0, 60) + "…" : content;
                String time     = createdAt.length() > 16 ? GRAY + "  " + createdAt.substring(0, 16) + RESET : "";
                println("  " + CYAN + "[" + idx + "]" + RESET + " " + preview + time);
                ids.add(msgId);
                idx++;
            }
            println("  " + GRAY + "[0] 取消" + RESET);
            println("");

            String input;
            try {
                input = reader.readLine(BOLD + "选择编号: " + RESET);
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                return;
            }
            if (input == null || input.isBlank()) return;

            int chosen;
            try {
                chosen = Integer.parseInt(input.trim());
            } catch (NumberFormatException e) {
                println(RED + "❌ 请输入有效编号" + RESET);
                return;
            }
            if (chosen == 0) return;
            if (chosen < 1 || chosen > ids.size()) {
                println(RED + "❌ 编号超出范围" + RESET);
                return;
            }

            long targetId = ids.get(chosen - 1);

            // 选项菜单
            println("");
            println(BOLD + "选择回退方式:" + RESET);
            println("  " + CYAN + "[1]" + RESET + " 回退对话上下文");
            println("  " + CYAN + "[2]" + RESET + " 取消");
            println("");

            String optInput;
            try {
                optInput = reader.readLine(BOLD + "选择: " + RESET);
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                return;
            }
            if (optInput == null || optInput.isBlank() || "2".equals(optInput.trim())) return;
            if (!"1".equals(optInput.trim())) {
                println(RED + "❌ 无效选项" + RESET);
                return;
            }

            // 执行回退
            JsonNode rewindResult = client.request("session.rewind", Map.of("messageId", targetId)).get();
            if (rewindResult != null && rewindResult.has("ok") && rewindResult.get("ok").asBoolean()) {
                println(GREEN + "✓ 已回退到消息 [" + chosen + "]，之后的对话已清除" + RESET);
            } else {
                println(RED + "❌ 回退失败" + RESET);
            }

        } catch (Exception e) {
            println(RED + "❌ 回退失败: " + e.getMessage() + RESET);
        }
    }

    private void showSessionInfo() {
        if (currentSessionId == null) {
            println(GRAY + "当前没有活动会话" + RESET);
        } else {
            println(BOLD + "当前会话:" + RESET);
            println("  ID: " + currentSessionId);
            println("  目录: " + workingDirectory);
        }
    }

    private void listSessions() {
        try {
            JsonNode result = client.request("session.list", Map.of()).get();
            if (result != null && result.has("sessions")) {
                JsonNode sessions = result.get("sessions");
                if (sessions.isEmpty()) {
                    println(GRAY + "没有历史会话" + RESET);
                } else {
                    println(BOLD + "历史会话:" + RESET);
                    for (JsonNode s : sessions) {
                        String id = s.has("id") ? s.get("id").asText() : "?";
                        String marker = id.equals(currentSessionId) ? " ← 当前" : "";
                        println("  " + CYAN + id.substring(0, Math.min(8, id.length())) + RESET + marker);
                    }
                }
            }
        } catch (Exception e) {
            println(RED + "❌ 获取会话列表失败: " + e.getMessage() + RESET);
        }
    }

    private void showContextStatus() {
        try {
            JsonNode result = client.request("session.status", Map.of()).get();
            if (result != null) {
                int contextLength = result.has("contextLength") ? result.get("contextLength").asInt() : 0;
                int thresholdTokens = result.has("thresholdTokens") ? result.get("thresholdTokens").asInt() : 0;
                double usagePercent = result.has("usagePercent") ? result.get("usagePercent").asDouble() : 0;
                println(BOLD + "上下文状态:" + RESET);
                println("  Token 使用: " + contextLength + " / " + thresholdTokens);
                println("  使用率: " + String.format("%.1f%%", usagePercent));
                println("  状态: " + (usagePercent > 75.0 ? YELLOW + "需要压缩" : GREEN + "正常") + RESET);
            }
        } catch (Exception e) {
            println(GRAY + "无法获取状态" + RESET);
        }
    }

    private void showMemory() {
        println(GRAY + "记忆系统状态:" + RESET);
        println("  - MEMORY.md: 静态记忆 (memory_read/write/append)");
        println("  - USER.md: 用户画像 (user_read/write/append)");
        println("  - session_search: 历史对话检索");
        println("");
        println(YELLOW + "提示: 在对话中直接请求 Agent 操作记忆" + RESET);
    }

    private void changeDirectory(String dir) {
        if (dir.isBlank()) { println(GRAY + "用法: /cd <目录>" + RESET); return; }
        try {
            JsonNode result = client.request("session.cd", Map.of("path", dir)).get();
            if (result != null && result.has("path")) {
                workingDirectory = Path.of(result.get("path").asText());
                completer.setWorkingDirectory(workingDirectory);
                println(GREEN + "✓ 切换到: " + RESET + workingDirectory);
            }
        } catch (Exception e) {
            println(RED + "❌ 切换目录失败: " + e.getMessage() + RESET);
        }
    }

    private void showWorkingDirectory() {
        try {
            JsonNode result = client.request("session.pwd", Map.of()).get();
            if (result != null && result.has("path")) {
                println("工作目录: " + result.get("path").asText());
            }
        } catch (Exception e) {
            println("工作目录: " + workingDirectory);
        }
    }

    private void clearScreen() {
        try {
            terminal.puts(org.jline.utils.InfoCmp.Capability.clear_screen);
            terminal.flush();
        } catch (Exception ignored) {}
    }

    private void println(String msg) { terminal.writer().println(msg); terminal.flush(); }
}