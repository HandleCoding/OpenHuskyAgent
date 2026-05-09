package io.github.huskyagent.tui;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.huskyagent.rpc.JsonRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class AgentTUI {

    private JsonRpcClient    client;
    private Terminal         terminal;
    private LineReader       reader;
    private AgentCompleter   completer;
    private CommandHandler   commandHandler;
    private MessageBoxRenderer boxRenderer;
    private ApprovalHandler  approvalHandler;

    private String currentSessionId;
    private Path   workingDirectory;
    private boolean running = true;

    // ANSI
    private static final String RESET = "\033[0m";
    private static final String BOLD  = "\033[1m";
    private static final String CYAN  = "\033[36m";
    private static final String GRAY  = "\033[90m";
    private static final String YELLOW= "\033[33m";
    private static final String RED   = "\033[31m";

    private static final String DEFAULT_SERVER_URL = "ws://localhost:18088/api/tui";

    public static void main(String[] args) {
        String serverUrl = DEFAULT_SERVER_URL;
        for (int i = 0; i < args.length; i++) {
            if ("--server".equals(args[i]) && i + 1 < args.length) {
                serverUrl = args[i + 1];
            }
        }

        AgentTUI tui = new AgentTUI();
        try {
            tui.start(serverUrl);
        } catch (Exception e) {
            System.err.println("TUI startup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }


    private void start(String serverUrl) throws Exception {
        initTerminal();
        printWelcome();

        println(GRAY + "  Connecting to service " + serverUrl + " ..." + RESET);
        client = new JsonRpcClient(serverUrl);
        registerEventHandlers();

        try {
            client.connect();
        } catch (Exception e) {
            println(RED + "  Connection failed: " + e.getMessage() + RESET);
            println(GRAY + "  Make sure the service is running: bin/husky serve" + RESET);
            shutdown();
            return;
        }

        println(GRAY + "  ✓ Connected" + RESET);
        println("");

        try {
            var result = client.request("session.create", Map.of()).get();
            if (result != null && result.has("sessionId")) {
                currentSessionId = result.get("sessionId").asText();
            }
        } catch (Exception e) {
            println(RED + "  Failed to create session: " + e.getMessage() + RESET);
        }

        workingDirectory = Path.of(System.getProperty("user.dir"));
        commandHandler = new CommandHandler(client, terminal, reader, completer,
                currentSessionId, workingDirectory);

        mainLoop();
        shutdown();
    }


    private void registerEventHandlers() {
        client.onEvent("message.delta", this::handleMessageDelta);
        client.onEvent("message.intermediate", this::handleMessageIntermediate);
        client.onEvent("message.complete", this::handleMessageComplete);
        client.onEvent("tool.started", this::handleToolStarted);
        client.onEvent("tool.completed", this::handleToolCompleted);
        client.onEvent("tool.failed", this::handleToolFailed);
        client.onEvent("todo.updated", this::handleTodoUpdated);
        client.onEvent("subagent.started", this::handleSubAgentStarted);
        client.onEvent("subagent.completed", this::handleSubAgentCompleted);
        client.onEvent("subagent.tool.started", this::handleSubAgentToolStarted);
        client.onEvent("subagent.tool.completed", this::handleSubAgentToolCompleted);
        client.onEvent("approval.request", this::handleApprovalRequest);
        client.onEvent("clarify.request", this::handleClarifyRequest);
        client.onEvent("error", this::handleError);
    }


    private void mainLoop() {
        while (running) {
            try {
                String prompt = buildPrompt();
                String line = reader.readLine(prompt);
                if (line == null || line.isBlank()) continue;
                line = line.trim();
                if (line.startsWith("/")) {
                    commandHandler.handle(line);
                    currentSessionId = commandHandler.getCurrentSessionId();
                    workingDirectory = commandHandler.getWorkingDirectory();
                    if (commandHandler.isExitRequested()) running = false;
                } else {
                    handleChat(line);
                }
            } catch (UserInterruptException e) {
                println("\nUse /exit to quit");
            } catch (EndOfFileException e) {
                running = false;
            } catch (Exception e) {
                log.error("Main loop failed", e);
                println(RED + "❌ Error: " + e.getMessage() + RESET);
            }
        }
    }


    private void handleChat(String message) {
        print(GRAY + "⏳ Thinking..." + RESET);
        boxRenderer.reset();

        try {
            CompletableFuture<JsonNode> future = client.request("prompt.submit", Map.of(
                    "text", message,
                    "sessionId", currentSessionId != null ? currentSessionId : ""
            ));

            future.exceptionally(ex -> {
                clearThinking();
                boxRenderer.closeBoxIfOpen();
                println(RED + "❌ Request failed: " + ex.getMessage() + RESET);
                return null;
            });

            while (!future.isDone()) {
                try {
                    future.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                }
                // Keep approval and clarification prompts on the terminal thread while chat work runs elsewhere.
                approvalHandler.drainPendingInteraction();
            }
            future.get();

            clearThinking();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            clearThinking();
            boxRenderer.closeBoxIfOpen();
            println(RED + "❌ Chat failed: " + e.getCause().getMessage() + RESET);
        }
    }


    private void handleMessageDelta(JsonNode payload) {
        String token = payload.has("token") ? payload.get("token").asText() : "";
        boolean reasoning = payload.has("reasoning") && payload.get("reasoning").asBoolean();
        boxRenderer.handleToken(token, reasoning, this::clearThinking);
    }

    private void handleMessageIntermediate(JsonNode payload) {
        boolean intermediate = payload.has("intermediate") && payload.get("intermediate").asBoolean();
        boxRenderer.handleIntermediate(intermediate);
    }

    private void handleMessageComplete(JsonNode payload) {
        long durationMs = payload.has("durationMs") ? payload.get("durationMs").asLong() : 0;
        boolean success = !payload.has("status") || "ok".equals(payload.get("status").asText());
        boolean streamed = payload.has("streamed") && payload.get("streamed").asBoolean();

        if (success) {
            boxRenderer.closeBox(durationMs);
        } else {
            boxRenderer.closeBoxIfOpen();
            String error = payload.has("error") ? payload.get("error").asText() : "Unknown error";
            println(RED + "❌ " + error + RESET);
        }
    }

    private void handleToolStarted(JsonNode payload) {
        ToolCallDisplay.printToolStarted(terminal, payload);
    }

    private void handleToolCompleted(JsonNode payload) {
        ToolCallDisplay.printToolCompleted(terminal, payload);
    }

    private void handleToolFailed(JsonNode payload) {
        ToolCallDisplay.printToolFailed(terminal, payload);
    }

    private void handleTodoUpdated(JsonNode payload) {
        JsonNode items = payload.get("items");
        ToolCallDisplay.printTodoPanel(items, terminal.writer(), terminal::flush);
    }

    private void handleSubAgentStarted(JsonNode payload) {
        int taskIndex = payload.has("taskIndex") ? payload.get("taskIndex").asInt() : 0;
        String goal = payload.has("goal") ? payload.get("goal").asText() : "";
        ToolCallDisplay.printSubAgentStart(taskIndex, goal, terminal.writer(), terminal::flush);
    }

    private void handleSubAgentCompleted(JsonNode payload) {
        int taskIndex = payload.has("taskIndex") ? payload.get("taskIndex").asInt() : 0;
        String status = payload.has("status") ? payload.get("status").asText() : "completed";
        long durationMs = payload.has("durationMs") ? payload.get("durationMs").asLong() : 0;
        String summary = payload.has("summary") ? payload.get("summary").asText() : "";
        ToolCallDisplay.printSubAgentEnd(taskIndex, status, durationMs, summary,
                terminal.writer(), terminal::flush);
    }

    private void handleSubAgentToolStarted(JsonNode payload) {
        int taskIndex = payload.has("taskIndex") ? payload.get("taskIndex").asInt() : 0;
        String toolName = payload.has("toolName") ? payload.get("toolName").asText() : "unknown";
        String argsPreview = payload.has("argsPreview") ? payload.get("argsPreview").asText() : "";
        ToolCallDisplay.printSubAgentToolEvent(taskIndex, "STARTED", toolName, argsPreview,
                0, null, terminal.writer(), terminal::flush);
    }

    private void handleSubAgentToolCompleted(JsonNode payload) {
        int taskIndex = payload.has("taskIndex") ? payload.get("taskIndex").asInt() : 0;
        String toolName = payload.has("toolName") ? payload.get("toolName").asText() : "unknown";
        long durationMs = payload.has("durationMs") ? payload.get("durationMs").asLong() : 0;
        boolean success = !payload.has("success") || payload.get("success").asBoolean(true);
        String error = payload.has("error") ? payload.get("error").asText() : null;
        String type = success ? "COMPLETED" : "FAILED";
        ToolCallDisplay.printSubAgentToolEvent(taskIndex, type, toolName, "",
                durationMs, error, terminal.writer(), terminal::flush);
    }

    private void handleApprovalRequest(JsonNode payload) {
        approvalHandler.handleFromEvent(client, payload);
    }

    private void handleClarifyRequest(JsonNode payload) {
        approvalHandler.handleClarifyFromEvent(client, payload);
    }

    private void handleError(JsonNode payload) {
        String message = payload.has("message") ? payload.get("message").asText() : "Unknown error";
        clearThinking();
        boxRenderer.closeBoxIfOpen();
        println(RED + "❌ " + message + RESET);
    }


    private void initTerminal() throws IOException {
        terminal        = TerminalBuilder.builder().system(true).build();
        completer       = new AgentCompleter();
        reader          = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new DefaultHistory())
                .parser(new DefaultParser())
                .completer(completer)
                .build();
        workingDirectory = Path.of(System.getProperty("user.dir"));
        boxRenderer      = new MessageBoxRenderer(terminal);
        approvalHandler  = new ApprovalHandler(reader, terminal.writer(), terminal::flush);
    }


    private void printWelcome() {
        println("");
        println(BOLD + CYAN + "╔══════════════════════════════════════════════════════════════╗" + RESET);
        println(BOLD + CYAN + "║" + RESET + BOLD + "            🐺 Husky — Your AI Workforce                      " + RESET + BOLD + CYAN + "║" + RESET);
        println(BOLD + CYAN + "║" + RESET + "        AI Agent Platform · Sub-Agent · Memory · MCP          " + RESET + BOLD + CYAN + "║" + RESET);
        println(BOLD + CYAN + "╚══════════════════════════════════════════════════════════════╝" + RESET);
        println("");
        println(GRAY + "  Working directory: " + RESET + workingDirectory);
        println(GRAY + "  Type " + RESET + YELLOW + "/help" + RESET + GRAY + " for commands, " + RESET + YELLOW + "/exit" + RESET + GRAY + " to exit" + RESET);
        println("");
    }

    private String buildPrompt() {
        String disconnected = client != null && !client.isConnected() ? RED + "[Disconnected] " + RESET : "";
        if (currentSessionId != null) {
            return disconnected + "❯ [" + currentSessionId.substring(0, Math.min(8, currentSessionId.length())) + "] ";
        }
        return disconnected + "❯ ";
    }

    private void clearThinking() {
        print("\r" + " ".repeat(60) + "\r");
    }

    private void println(String msg) { terminal.writer().println(msg); terminal.flush(); }
    private void print(String msg)   { terminal.writer().print(msg);   terminal.flush(); }

    private void shutdown() {
        try {
            if (client != null) client.disconnect();
            if (reader != null) reader.getHistory().save();
            if (terminal != null) terminal.close();
        } catch (Exception e) {
            log.debug("Exception during shutdown", e);
        }
    }
}