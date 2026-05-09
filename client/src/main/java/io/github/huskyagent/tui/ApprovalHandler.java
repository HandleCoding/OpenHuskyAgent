package io.github.huskyagent.tui;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.huskyagent.rpc.JsonRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.LineReader;

import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * 审批交互处理：接收 JSON-RPC approval.request 事件，渲染表单，发送 approval.respond。
 *
 * <p><b>线程模型：</b>
 * approval.request 事件由 WebSocket IO 线程触发，但 {@code reader.readLine()} 只能在
 * 持有终端的主线程中调用（JLine 限制）。
 *
 * <p>解决方案：使用两个 {@link SynchronousQueue} 在 IO 线程与主线程之间传递审批请求/响应：
 * <ol>
 *   <li>IO 线程将 {@link PendingApproval} 放入 {@code requestQueue}（会阻塞直到主线程取走）</li>
 *   <li>主线程调用 {@link #drainPendingApproval()} 取出请求，调用 {@code readLine()} 获取输入，
 *       然后将 {@code boolean[]} 结果放入 {@code responseQueue}</li>
 *   <li>IO 线程从 {@code responseQueue} 拿到结果后发送 approval.respond</li>
 * </ol>
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

    /**
     * IO 线程 → 主线程：待处理的审批请求
     */
    private final SynchronousQueue<PendingApproval> requestQueue  = new SynchronousQueue<>();
    /**
     * IO 线程 → 主线程：待处理的澄清请求
     */
    private final SynchronousQueue<PendingClarify> clarifyQueue = new SynchronousQueue<>();
    /**
     * 主线程 → IO 线程：用户决策结果 {allowed, alwaysAllow}
     */
    private final SynchronousQueue<boolean[]>       responseQueue = new SynchronousQueue<>();
    /**
     * 主线程 → IO 线程：澄清回答
     */
    private final SynchronousQueue<String> clarifyResponseQueue = new SynchronousQueue<>();

    ApprovalHandler(LineReader reader, java.io.PrintWriter out, Runnable flush) {
        this.reader = reader;
        this.out    = out;
        this.flush  = flush;
    }

    // ── 主线程调用 ──────────────────────────────────────────────────────────

    /**
     * 由主线程轮询：如果有待处理的审批请求，交互式读取用户决策并写回 responseQueue。
     *
     * <p>在 {@code handleChat()} 的 {@code future.get()} 等待期间周期性调用。</p>
     *
     * @return {@code true} 表示处理了一次审批请求
     */
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
            log.warn("审批响应队列被中断");
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
            log.warn("澄清响应队列被中断");
        }
        return true;
    }

    // ── IO 线程调用 ─────────────────────────────────────────────────────────

    /** 从 JSON-RPC 事件处理审批请求（由 WebSocket IO 线程调用） */
    void handleFromEvent(JsonRpcClient client, JsonNode payload) {
        String requestId = payload.has("requestId") ? payload.get("requestId").asText() : null;
        String toolName  = payload.has("toolName")  ? payload.get("toolName").asText()  : "unknown";
        String toolArgs  = payload.has("toolArgs")   ? payload.get("toolArgs").asText()   : "{}";
        String reason    = payload.has("reason")     ? payload.get("reason").asText()     : null;

        // 将请求交给主线程处理（阻塞直到主线程取走）
        PendingApproval approval = new PendingApproval(toolName, toolArgs, reason);
        try {
            boolean offered = requestQueue.offer(approval, 30, TimeUnit.SECONDS);
            if (!offered) {
                log.warn("审批请求队列超时，自动拒绝: {}", toolName);
                sendResponse(client, requestId, new boolean[]{false, false});
                return;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            sendResponse(client, requestId, new boolean[]{false, false});
            return;
        }

        // 等待主线程填入决策结果
        boolean[] decision;
        try {
            decision = responseQueue.poll(30, TimeUnit.SECONDS);
            if (decision == null) {
                log.warn("等待审批决策超时，自动拒绝: {}", toolName);
                decision = new boolean[]{false, false};
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            decision = new boolean[]{false, false};
        }

        sendResponse(client, requestId, decision);

        String text = decision[0]
                ? GREEN + "✓ 已允许" + (decision[1] ? "（本次会话不再询问）" : "") + RESET
                : RED + "✗ 已拒绝" + RESET;
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
                log.warn("澄清请求队列超时: {}", question);
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
                log.warn("等待澄清回答超时: {}", question);
                answer = "";
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            answer = "";
        }

        sendClarifyResponse(client, requestId, answer);
        println(GREEN + "✓ 已回答" + RESET);
        println("");
    }

    // ── 私有方法 ────────────────────────────────────────────────────────────

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
        println(BOLD + YELLOW + "⚠️  需要审批" + RESET);
        println(YELLOW + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        println(BOLD + "工具: " + RESET + toolName);

        String args = toolArgs;
        if (args != null && args.length() > 300) args = args.substring(0, 300) + "...";
        println(BOLD + "参数: " + RESET + args);

        if (reason != null && !reason.isBlank()) {
            println(BOLD + "原因: " + RESET + RED + reason + RESET);
        }

        println(YELLOW + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        println(GRAY + "  [y] 允许  [n] 拒绝  [a] 本次会话总是允许" + RESET);
        println("");
    }

    private void printClarifyRequest(String question, java.util.List<String> options) {
        println("");
        println(BOLD + YELLOW + "?  需要澄清" + RESET);
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

    /** 必须在主线程调用 */
    private boolean[] readDecision() {
        try {
            String response = reader.readLine(BOLD + "审批 [y/n/a]: " + RESET).trim().toLowerCase();
            return switch (response) {
                case "y", "yes"    -> new boolean[]{true, false};
                case "a", "always" -> new boolean[]{true, true};
                default            -> new boolean[]{false, false};
            };
        } catch (Exception e) {
            log.error("读取审批输入失败", e);
            return new boolean[]{false, false};
        }
    }

    private String readClarifyAnswer(java.util.List<String> options) {
        try {
            String response = reader.readLine(BOLD + "回答: " + RESET).trim();
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
            log.error("读取澄清输入失败", e);
            return "";
        }
    }

    private void println(String msg) { out.println(msg); flush.run(); }

    // ── 内嵌类型 ────────────────────────────────────────────────────────────

    private record PendingApproval(String toolName, String toolArgs, String reason) {}
    private record PendingClarify(String question, java.util.List<String> options) {}
}