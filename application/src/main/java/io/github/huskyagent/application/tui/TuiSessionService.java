package io.github.huskyagent.application.tui;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.agent.ApprovalContext;
import io.github.huskyagent.application.agent.ClarifyContext;
import io.github.huskyagent.application.channel.ChannelInboundQueue;
import io.github.huskyagent.application.runtime.RuntimeExecutionRequest;
import io.github.huskyagent.application.runtime.RuntimeExecutionResult;
import io.github.huskyagent.application.runtime.RuntimeExecutionService;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.application.session.SessionOperationsService;
import io.github.huskyagent.application.session.SessionResolver;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.context.ContextStatus;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * TUI 模式编排服务 — per-connection scope。
 *
 * <p>每个 WebSocket 连接持有一个实例。
 * Session 生命周期完全走 {@link SessionResolver}，与 HTTP chatbot 路径对称。</p>
 */
@Slf4j
public class TuiSessionService {

    private final RuntimeExecutionService runtimeExecutionService;
    private final SessionResolver sessionResolver;
    private final SessionOperationsService sessionOperationsService;
    private final ChannelInboundQueue inboundQueue;
    private final Executor executor;
    private final String connectionId;
    private final String queueKey;

    /** TUI 固定身份 — per-connection，不可变 */
    private final Principal principal;
    private final ChannelIdentity channelIdentity;

    /** 当前会话 ID */
    private volatile String currentSessionId;
    /** 本连接的工作目录（不再影响其他连接） */
    private volatile Path workingDirectory = Path.of(System.getProperty("user.dir"));

    private volatile boolean closed = false;

    /** 待响应的审批请求：requestId → ApprovalWait */
    private final ConcurrentHashMap<String, ApprovalWait> pendingApprovals = new ConcurrentHashMap<>();

    /** 待响应的澄清请求：requestId → ClarifyWait */
    private final ConcurrentHashMap<String, ClarifyWait> pendingClarifications = new ConcurrentHashMap<>();

    public TuiSessionService(RuntimeExecutionService runtimeExecutionService,
                             SessionResolver sessionResolver,
                             SessionOperationsService sessionOperationsService,
                             ChannelInboundQueue inboundQueue,
                             Executor executor,
                             String connectionId) {
        this.runtimeExecutionService = runtimeExecutionService;
        this.sessionResolver = sessionResolver;
        this.sessionOperationsService = sessionOperationsService;
        this.inboundQueue = inboundQueue;
        this.executor = executor;
        this.connectionId = connectionId;
        this.queueKey = "tui-connection:" + connectionId;
        this.principal = Principal.builder()
                .id("local:default")
                .displayName("Local User")
                .channelType(ChannelType.TUI)
                .build();
        this.channelIdentity = ChannelIdentity.builder()
                .channelType(ChannelType.TUI)
                .conversationType(ConversationType.DIRECT)
                .connectionId(connectionId)
                .build();
    }

    // ── 会话管理 ────────────────────────────────────────────────────────────

    public String createSession() {
        RuntimeScope scope = sessionResolver.createSession(principal, channelIdentity, null);
        this.currentSessionId = scope.getSessionId();
        return currentSessionId;
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public List<?> listSessions() {
        return sessionResolver.listSessions(principal, channelIdentity, null);
    }

    public List<io.github.huskyagent.infra.session.MessageEntity> listUserMessages() {
        if (currentSessionId == null) return List.of();
        return sessionOperationsService.listUserMessages(currentSessionId);
    }

    public void rewindTo(long afterMessageId) {
        if (currentSessionId == null) throw new IllegalStateException("没有活动会话");
        sessionOperationsService.rewindTo(currentSessionId, afterMessageId);
    }

    /** 切换到已有会话，返回切换后的 sessionId；若 sessionId 不存在则抛异常 */
    public String switchSession(String sessionId) {
        try {
            RuntimeScope scope = sessionResolver.resolveOrCreateSession(principal, channelIdentity, null, sessionId);
            this.currentSessionId = scope.getSessionId();
            return currentSessionId;
        } catch (SecurityException e) {
            throw new IllegalArgumentException("会话不可访问: " + sessionId, e);
        }
    }

    public int countMessages(String sessionId) {
        return sessionOperationsService.countMessages(sessionId);
    }

    public ContextStatus getContextStatus(String sessionId) {
        return sessionId != null
                ? sessionOperationsService.getContextStatus(sessionId)
                : sessionOperationsService.getContextStatus();
    }

    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    /** 切换工作目录 — 只影响本连接，不影响其他连接 */
    public Path changeDirectory(String dir) {
        Path newDir = workingDirectory.resolve(dir).normalize();
        if (!java.nio.file.Files.isDirectory(newDir)) {
            throw new IllegalArgumentException("目录不存在: " + newDir);
        }
        workingDirectory = newDir;
        return workingDirectory;
    }

    // ── 对话执行 ────────────────────────────────────────────────────────────

    public ChatResult submitPrompt(String message, JsonRpcEventEmitter emitter) {
        return submitPrompt(message, emitter, null);
    }

    public ChatResult submitPrompt(String message, JsonRpcEventEmitter emitter, Consumer<String> sessionReadyHandler) {
        PreparedPrompt prepared = preparePrompt(message, sessionReadyHandler);
        return inboundQueue.enqueue(queueKey, () -> executePrompt(prepared, emitter), executor).join();
    }

    private synchronized PreparedPrompt preparePrompt(String message, Consumer<String> sessionReadyHandler) {
        if (currentSessionId == null) {
            createSession();
        }
        if (sessionReadyHandler != null) {
            sessionReadyHandler.accept(currentSessionId);
        }
        return new PreparedPrompt(message, currentSessionId, workingDirectory);
    }

    private ChatResult executePrompt(PreparedPrompt prepared, JsonRpcEventEmitter emitter) {
        if (closed) {
            return ChatResult.failure("Connection closed");
        }
        long startTime = System.currentTimeMillis();
        try {
            RuntimeExecutionResult executionResult = runtimeExecutionService.execute(RuntimeExecutionRequest.builder()
                    .inbound(buildInboundMessage(prepared.message(), prepared.sessionId()))
                    .workingDirectoryOverride(prepared.workingDirectory())
                    .callbacks(new TuiRuntimeCallbacks(this, emitter))
                    .build());
            if (executionResult.scope() != null) {
                this.currentSessionId = executionResult.scope().getSessionId();
            }
            ChatResult result = executionResult.chatResult();

            long durationMs = System.currentTimeMillis() - startTime;
            emitter.emitMessageComplete(
                    result.content(),
                    result.success() ? "ok" : "error",
                    durationMs,
                    result.streamed()
            );

            return result;
        } catch (Exception e) {
            log.error("TUI 对话执行异常", e);
            emitter.emitError(e.getMessage());
            return ChatResult.failure(e.getMessage());
        } finally {
            pendingApprovals.clear();
            pendingClarifications.clear();
        }
    }

    // ── 审批响应 ────────────────────────────────────────────────────────────

    public boolean respondApproval(String requestId, String choice, boolean always) {
        ApprovalWait wait = pendingApprovals.get(requestId);
        if (wait == null) {
            log.warn("未找到审批请求: requestId={}, connectionId={}", requestId, connectionId);
            return false;
        }
        wait.setChoice(choice, always);
        wait.latch().countDown();
        return true;
    }

    public boolean respondClarify(String requestId, String answer) {
        ClarifyWait wait = pendingClarifications.get(requestId);
        if (wait == null) {
            log.warn("未找到澄清请求: requestId={}, connectionId={}", requestId, connectionId);
            return false;
        }
        wait.setAnswer(answer);
        wait.latch().countDown();
        return true;
    }

    public void close() {
        closed = true;
        cancelPendingApprovals();
    }

    /** 取消所有待审批（连接断开时调用） */
    public void cancelPendingApprovals() {
        pendingApprovals.values().forEach(wait -> {
            wait.setChoice("deny", false);
            wait.latch().countDown();
        });
        pendingApprovals.clear();
        pendingClarifications.values().forEach(wait -> {
            wait.setAnswer("");
            wait.latch().countDown();
        });
        pendingClarifications.clear();
    }

    // ── 私有方法 ────────────────────────────────────────────────────────────

    private io.github.huskyagent.infra.channel.InboundMessage buildInboundMessage(String message, String sessionId) {
        return io.github.huskyagent.infra.channel.InboundMessage.builder()
                .text(message)
                .requestedSessionId(sessionId)
                .principal(principal)
                .channelIdentity(channelIdentity)
                .build();
    }

    void handleApprovalRequest(ApprovalContext ctx, JsonRpcEventEmitter emitter) {
        String requestId = UUID.randomUUID().toString();
        emitter.emitApprovalRequest(requestId, ctx.toolName(), ctx.toolArgs(),
                ctx.reason(), ctx.agentText());

        ApprovalWait wait = new ApprovalWait();
        pendingApprovals.put(requestId, wait);

        try {
            boolean completed = wait.latch().await(5, TimeUnit.MINUTES);
            if (!completed) {
                log.warn("审批超时: requestId={}, tool={}, connectionId={}", requestId, ctx.toolName(), connectionId);
                emitter.emitError("审批超时，已自动拒绝");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pendingApprovals.remove(requestId);
        }

        String choice = wait.choice();
        boolean approved = "allow".equals(choice);
        boolean always = wait.always();
        ctx.approve(approved, always);
    }

    void handleClarifyRequest(ClarifyContext ctx, JsonRpcEventEmitter emitter) {
        String requestId = UUID.randomUUID().toString();
        emitter.emitClarifyRequest(requestId, ctx.question(), ctx.options(), ctx.agentText());

        ClarifyWait wait = new ClarifyWait();
        pendingClarifications.put(requestId, wait);

        try {
            boolean completed = wait.latch().await(5, TimeUnit.MINUTES);
            if (!completed) {
                log.warn("澄清超时: requestId={}, connectionId={}", requestId, connectionId);
                emitter.emitError("澄清超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pendingClarifications.remove(requestId);
        }

        ctx.respond(wait.answer());
    }

    // ── 内嵌类型 ────────────────────────────────────────────────────────────

    private record PreparedPrompt(String message, String sessionId, Path workingDirectory) {}

    private static class ApprovalWait {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String choice = "deny";
        private volatile boolean always = false;

        CountDownLatch latch() { return latch; }
        String choice() { return choice; }
        boolean always() { return always; }

        void setChoice(String choice, boolean always) {
            this.choice = choice;
            this.always = always;
        }
    }

    private static class ClarifyWait {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String answer = "";

        CountDownLatch latch() { return latch; }
        String answer() { return answer; }

        void setAnswer(String answer) {
            this.answer = answer != null ? answer : "";
        }
    }
}
