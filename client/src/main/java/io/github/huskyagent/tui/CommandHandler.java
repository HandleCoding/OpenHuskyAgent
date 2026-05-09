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


    void handle(String command) {
        String[] parts = command.split("\\s+", 2);
        String cmd  = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/help"                 -> printHelp();
            case "/exit", "/quit", "/q" -> { println(BLUE + "Goodbye! 👋" + RESET); exitRequested = true; }
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
            default -> println(RED + "❌ Unknown command: " + cmd + ". Type /help for help" + RESET);
        }
    }

    boolean isExitRequested()    { return exitRequested; }
    String  getCurrentSessionId(){ return currentSessionId; }
    Path    getWorkingDirectory() { return workingDirectory; }


    private void printHelp() {
        println("");
        println(BOLD + "Commands:" + RESET);
        println("  " + YELLOW + "/help" + RESET + "      - Show help");
        println("  " + YELLOW + "/new" + RESET + "       - Create a new session");
        println("  " + YELLOW + "/resume" + RESET + "    - Resume a previous session interactively");
        println("  " + YELLOW + "/rewind" + RESET + "    - Rewind to a previous message");
        println("  " + YELLOW + "/session" + RESET + "   - Show current session information");
        println("  " + YELLOW + "/sessions" + RESET + "  - List all sessions");
        println("  " + YELLOW + "/status" + RESET + "    - Show context status");
        println("  " + YELLOW + "/memory" + RESET + "    - Show memory status");
        println("  " + YELLOW + "/cd <dir>" + RESET + "  - Change working directory");
        println("  " + YELLOW + "/pwd" + RESET + "       - Show current directory");
        println("  " + YELLOW + "/clear" + RESET + "     - Clear the screen");
        println("  " + YELLOW + "/exit" + RESET + "      - Exit");
        println("");
    }

    private void createNewSession() {
        try {
            JsonNode result = client.request("session.create", Map.of()).get();
            if (result != null && result.has("sessionId")) {
                currentSessionId = result.get("sessionId").asText();
                println(GREEN + "✓ Created new session " + CYAN + currentSessionId.substring(0, 8) + RESET);
            }
        } catch (Exception e) {
            println(RED + "❌ Failed to create session: " + e.getMessage() + RESET);
        }
    }

    private void resumeSession(String args) {
        try {
            JsonNode result = client.request("session.list", Map.of()).get();
            if (result == null || !result.has("sessions")) {
                println(GRAY + "Unable to fetch session list" + RESET);
                return;
            }
            JsonNode sessions = result.get("sessions");
            if (sessions.isEmpty()) {
                println(GRAY + "No previous sessions" + RESET);
                return;
            }

            if (!args.isBlank()) {
                for (JsonNode s : sessions) {
                    String id = s.has("id") ? s.get("id").asText() : "";
                    if (id.startsWith(args)) {
                        doSwitch(id);
                        return;
                    }
                }
                println(RED + "❌ No matching session found: " + args + RESET);
                return;
            }

            println("");
            println(BOLD + "Previous sessions:" + RESET);
            List<String> ids = new ArrayList<>();
            int idx = 1;
            for (JsonNode s : sessions) {
                String id   = s.has("id")        ? s.get("id").asText()        : "?";
                String updAt = s.has("updatedAt") ? s.get("updatedAt").asText() : "";
                String msgs  = s.has("messageCount") ? s.get("messageCount").asText() : "";
                String marker = id.equals(currentSessionId) ? YELLOW + " ← current" + RESET : "";
                String info = updAt.isBlank() ? "" : GRAY + "  " + updAt.substring(0, Math.min(16, updAt.length())) + RESET;
                println("  " + CYAN + "[" + idx + "]" + RESET + " " + id.substring(0, Math.min(8, id.length())) + info + marker);
                ids.add(id);
                idx++;
            }
            println("");

            String input;
            try {
                input = reader.readLine(BOLD + "Select session number (Enter to cancel): " + RESET);
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                return;
            }
            if (input == null || input.isBlank()) return;

            int chosen;
            try {
                chosen = Integer.parseInt(input.trim());
            } catch (NumberFormatException e) {
                println(RED + "❌ Please enter a valid number" + RESET);
                return;
            }
            if (chosen < 1 || chosen > ids.size()) {
                println(RED + "❌ Number out of range" + RESET);
                return;
            }
            doSwitch(ids.get(chosen - 1));

        } catch (Exception e) {
            println(RED + "❌ Failed to fetch session list: " + e.getMessage() + RESET);
        }
    }

    private void doSwitch(String sessionId) {
        try {
            JsonNode result = client.request("session.switch", Map.of("sessionId", sessionId)).get();
            if (result != null && result.has("sessionId")) {
                currentSessionId = result.get("sessionId").asText();
                println(GREEN + "✓ Switched to session " + CYAN + currentSessionId.substring(0, 8) + RESET);
            } else {
                println(RED + "❌ Switch failed" + RESET);
            }
        } catch (Exception e) {
            println(RED + "❌ Failed to switch session: " + e.getMessage() + RESET);
        }
    }

    private void rewindSession() {
        try {
            JsonNode result = client.request("session.user-messages", Map.of()).get();
            if (result == null || !result.has("messages")) {
                println(GRAY + "Unable to fetch message list" + RESET);
                return;
            }
            JsonNode messages = result.get("messages");
            if (messages.isEmpty()) {
                println(GRAY + "Current session has no messages" + RESET);
                return;
            }

            println("");
            println(BOLD + "Select the message to rewind to; messages after it will be deleted:" + RESET);
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
            println("  " + GRAY + "[0] Cancel" + RESET);
            println("");

            String input;
            try {
                input = reader.readLine(BOLD + "Select number: " + RESET);
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                return;
            }
            if (input == null || input.isBlank()) return;

            int chosen;
            try {
                chosen = Integer.parseInt(input.trim());
            } catch (NumberFormatException e) {
                println(RED + "❌ Please enter a valid number" + RESET);
                return;
            }
            if (chosen == 0) return;
            if (chosen < 1 || chosen > ids.size()) {
                println(RED + "❌ Number out of range" + RESET);
                return;
            }

            long targetId = ids.get(chosen - 1);

            println("");
            println(BOLD + "Select rewind mode:" + RESET);
            println("  " + CYAN + "[1]" + RESET + " Rewind conversation context");
            println("  " + CYAN + "[2]" + RESET + " Cancel");
            println("");

            String optInput;
            try {
                optInput = reader.readLine(BOLD + "Select: " + RESET);
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                return;
            }
            if (optInput == null || optInput.isBlank() || "2".equals(optInput.trim())) return;
            if (!"1".equals(optInput.trim())) {
                println(RED + "❌ Invalid option" + RESET);
                return;
            }

            JsonNode rewindResult = client.request("session.rewind", Map.of("messageId", targetId)).get();
            if (rewindResult != null && rewindResult.has("ok") && rewindResult.get("ok").asBoolean()) {
                println(GREEN + "✓ Rewound to message [" + chosen + "]; later conversation has been cleared" + RESET);
            } else {
                println(RED + "❌ Rewind failed" + RESET);
            }

        } catch (Exception e) {
            println(RED + "❌ Rewind failed: " + e.getMessage() + RESET);
        }
    }

    private void showSessionInfo() {
        if (currentSessionId == null) {
            println(GRAY + "No active session" + RESET);
        } else {
            println(BOLD + "Current session:" + RESET);
            println("  ID: " + currentSessionId);
            println("  Directory: " + workingDirectory);
        }
    }

    private void listSessions() {
        try {
            JsonNode result = client.request("session.list", Map.of()).get();
            if (result != null && result.has("sessions")) {
                JsonNode sessions = result.get("sessions");
                if (sessions.isEmpty()) {
                    println(GRAY + "No previous sessions" + RESET);
                } else {
                    println(BOLD + "Previous sessions:" + RESET);
                    for (JsonNode s : sessions) {
                        String id = s.has("id") ? s.get("id").asText() : "?";
                        String marker = id.equals(currentSessionId) ? " ← current" : "";
                        println("  " + CYAN + id.substring(0, Math.min(8, id.length())) + RESET + marker);
                    }
                }
            }
        } catch (Exception e) {
            println(RED + "❌ Failed to fetch session list: " + e.getMessage() + RESET);
        }
    }

    private void showContextStatus() {
        try {
            JsonNode result = client.request("session.status", Map.of()).get();
            if (result != null) {
                int contextLength = result.has("contextLength") ? result.get("contextLength").asInt() : 0;
                int thresholdTokens = result.has("thresholdTokens") ? result.get("thresholdTokens").asInt() : 0;
                double usagePercent = result.has("usagePercent") ? result.get("usagePercent").asDouble() : 0;
                println(BOLD + "Context status:" + RESET);
                println("  Token usage: " + contextLength + " / " + thresholdTokens);
                println("  Usage: " + String.format("%.1f%%", usagePercent));
                println("  Status: " + (usagePercent > 75.0 ? YELLOW + "needs compression" : GREEN + "normal") + RESET);
            }
        } catch (Exception e) {
            println(GRAY + "Unable to fetch status" + RESET);
        }
    }

    private void showMemory() {
        println(GRAY + "Memory system status:" + RESET);
        println("  - MEMORY.md: static memory (memory_read/write/append)");
        println("  - USER.md: user profile (user_read/write/append)");
        println("  - session_search: conversation history search");
        println("");
        println(YELLOW + "Tip: ask the agent directly in chat to manage memory" + RESET);
    }

    private void changeDirectory(String dir) {
        if (dir.isBlank()) { println(GRAY + "Usage: /cd <directory>" + RESET); return; }
        try {
            JsonNode result = client.request("session.cd", Map.of("path", dir)).get();
            if (result != null && result.has("path")) {
                workingDirectory = Path.of(result.get("path").asText());
                completer.setWorkingDirectory(workingDirectory);
                println(GREEN + "✓ Changed to: " + RESET + workingDirectory);
            }
        } catch (Exception e) {
            println(RED + "❌ Failed to change directory: " + e.getMessage() + RESET);
        }
    }

    private void showWorkingDirectory() {
        try {
            JsonNode result = client.request("session.pwd", Map.of()).get();
            if (result != null && result.has("path")) {
                println("Working directory: " + result.get("path").asText());
            }
        } catch (Exception e) {
            println("Working directory: " + workingDirectory);
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