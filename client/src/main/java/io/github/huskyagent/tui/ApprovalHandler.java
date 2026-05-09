package io.github.huskyagent.tui;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.huskyagent.rpc.JsonRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.LineReader;

import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bridges approval and clarification events from the JSON-RPC IO thread to the terminal thread.
 *
 * <p>WebSocket event handlers cannot call {@code reader.readLine()} directly, so
 * the request and response queues hand terminal input back to the main thread.</p>
 */
@Slf4j
class ApprovalHandler {

    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String GRAY   = "\033[90m";
    private static final String RED    = "\033[31m";

    private final LineReader reader;
    private final java.io.PrintWriter out;
    private final Runnable flush;

    /** Keeps terminal-only reads on the main thread while the IO thread waits for a response. */
    private final SynchronousQueue<PendingApproval> requestQueue  = new SynchronousQueue<>();
    private final SynchronousQueue<PendingClarify> clarifyQueue = new SynchronousQueue<>();
    private final SynchronousQueue<boolean[]>       responseQueue = new SynchronousQueue<>();
    private final SynchronousQueue<String> clarifyResponseQueue = new SynchronousQueue<>();

    ApprovalHandler(LineReader reader, java.io.PrintWriter out, Runnable flush) {
        this.reader = reader;
        this.out    = out;
        this.flush  = flush;
    }


    boolean drainPendingInteraction() {
        return drainPendingApproval() || drainPendingClarify();
    }

    boolean drainPendingApproval() {
        PendingApproval pending = requestQueue.poll();
        if (pending == null) return false;

        printRequest(pending.toolName(), pending.toolArgs(), pending.reason());
        boolean[] decision = readDecision();
        try {
            responseQueue.put(decision);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Approval response queue was interrupted");
        }
        return true;
    }

    boolean drainPendingClarify() {
        PendingClarify pending = clarifyQueue.poll();
        if (pending == null) return false;

        printClarifyRequest(pending.question(), pending.options());
        String answer = readClarifyAnswer(pending.options());
        try {
            clarifyResponseQueue.put(answer);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Clarification response queue was interrupted");
        }
        return true;
    }


    void handleFromEvent(JsonRpcClient client, JsonNode payload) {
        String requestId = payload.has("requestId") ? payload.get("requestId").asText() : null;
        String toolName  = payload.has("toolName")  ? payload.get("toolName").asText()  : "unknown";
        String toolArgs  = payload.has("toolArgs")   ? payload.get("toolArgs").asText()   : "{}";
        String reason    = payload.has("reason")     ? payload.get("reason").asText()     : null;

        PendingApproval approval = new PendingApproval(toolName, toolArgs, reason);
        try {
            boolean offered = requestQueue.offer(approval, 30, TimeUnit.SECONDS);
            if (!offered) {
                log.warn("Approval request queue timed out; auto-rejecting: {}", toolName);
                sendResponse(client, requestId, new boolean[]{false, false});
                return;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            sendResponse(client, requestId, new boolean[]{false, false});
            return;
        }

        boolean[] decision;
        try {
            decision = responseQueue.poll(30, TimeUnit.SECONDS);
            if (decision == null) {
                log.warn("Timed out waiting for approval decision; auto-rejecting: {}", toolName);
                decision = new boolean[]{false, false};
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            decision = new boolean[]{false, false};
        }

        sendResponse(client, requestId, decision);

        String text = decision[0]
                ? GREEN + "✓ Allowed" + (decision[1] ? " for this session" : "") + RESET
                : RED + "✗ Denied" + RESET;
        println(text);
        println("");
    }

    void handleClarifyFromEvent(JsonRpcClient client, JsonNode payload) {
        String requestId = payload.has("requestId") ? payload.get("requestId").asText() : null;
        String question = payload.has("question") ? payload.get("question").asText() : "";
        java.util.List<String> options = new java.util.ArrayList<>();
        JsonNode optionNode = payload.get("options");
        if (optionNode != null && optionNode.isArray()) {
            optionNode.forEach(node -> options.add(node.asText()));
        }

        try {
            boolean offered = clarifyQueue.offer(new PendingClarify(question, options), 30, TimeUnit.SECONDS);
            if (!offered) {
                log.warn("Clarification request queue timed out: {}", question);
                sendClarifyResponse(client, requestId, "");
                return;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            sendClarifyResponse(client, requestId, "");
            return;
        }

        String answer;
        try {
            answer = clarifyResponseQueue.poll(30, TimeUnit.SECONDS);
            if (answer == null) {
                log.warn("Timed out waiting for clarification answer: {}", question);
                answer = "";
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            answer = "";
        }

        sendClarifyResponse(client, requestId, answer);
        println(GREEN + "✓ Answered" + RESET);
        println("");
    }


    private void sendResponse(JsonRpcClient client, String requestId, boolean[] decision) {
        String choice = decision[0] ? "allow" : "deny";
        client.request("approval.respond", Map.of(
                "requestId", requestId != null ? requestId : "",
                "choice", choice,
                "all", decision[1]
        ));
    }

    private void sendClarifyResponse(JsonRpcClient client, String requestId, String answer) {
        client.request("clarify.respond", Map.of(
                "requestId", requestId != null ? requestId : "",
                "answer", answer != null ? answer : ""
        ));
    }

    private void printRequest(String toolName, String toolArgs, String reason) {
        println("");
        println(BOLD + YELLOW + "⚠️  Approval required" + RESET);
        println(YELLOW + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        println(BOLD + "Tool: " + RESET + toolName);

        String args = toolArgs;
        if (args != null && args.length() > 300) args = args.substring(0, 300) + "...";
        println(BOLD + "Arguments: " + RESET + args);

        if (reason != null && !reason.isBlank()) {
            println(BOLD + "Reason: " + RESET + RED + reason + RESET);
        }

        println(YELLOW + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        println(GRAY + "  [y] Allow  [n] Deny  [a] Always allow for this session" + RESET);
        println("");
    }

    private void printClarifyRequest(String question, java.util.List<String> options) {
        println("");
        println(BOLD + YELLOW + "?  Clarification required" + RESET);
        println(YELLOW + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        println(BOLD + question + RESET);
        if (options != null && !options.isEmpty()) {
            for (int i = 0; i < options.size(); i++) {
                println("  " + (i + 1) + ". " + options.get(i));
            }
        }
        println(YELLOW + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        println("");
    }

    private boolean[] readDecision() {
        try {
            String response = reader.readLine(BOLD + "Approve [y/n/a]: " + RESET).trim().toLowerCase();
            return switch (response) {
                case "y", "yes"    -> new boolean[]{true, false};
                case "a", "always" -> new boolean[]{true, true};
                default            -> new boolean[]{false, false};
            };
        } catch (Exception e) {
            log.error("Failed to read approval input", e);
            return new boolean[]{false, false};
        }
    }

    private String readClarifyAnswer(java.util.List<String> options) {
        try {
            String response = reader.readLine(BOLD + "Answer: " + RESET).trim();
            if (options != null && !options.isEmpty()) {
                try {
                    int index = Integer.parseInt(response);
                    if (index >= 1 && index <= options.size()) {
                        return options.get(index - 1);
                    }
                } catch (NumberFormatException ignored) {
                    // Treat free-form text as the answer.
                }
            }
            return response;
        } catch (Exception e) {
            log.error("Failed to read clarification input", e);
            return "";
        }
    }

    private void println(String msg) { out.println(msg); flush.run(); }


    private record PendingApproval(String toolName, String toolArgs, String reason) {}
    private record PendingClarify(String question, java.util.List<String> options) {}
}