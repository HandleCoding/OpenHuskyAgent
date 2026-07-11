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

@Slf4j
public class TuiSessionService {

    private final RuntimeExecutionService runtimeExecutionService;
    private final SessionResolver sessionResolver;
    private final SessionOperationsService sessionOperationsService;
    private final ChannelInboundQueue inboundQueue;
    private final Executor executor;
    private final String connectionId;
    private final String queueKey;

    private final Principal principal;
    private final ChannelIdentity channelIdentity;

    private volatile String currentSessionId;
    private volatile Path workingDirectory = Path.of(System.getProperty("user.dir"));

    private volatile boolean closed = false;

    private final ConcurrentHashMap<String, ApprovalWait> pendingApprovals = new ConcurrentHashMap<>();

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


    public synchronized String createSession() {
        runtimeExecutionService.runCoordinator().bumpQueueGeneration(queueKey);
        interruptCurrentRun(null, "new_session");
        RuntimeScope scope = sessionResolver.createSession(principal, channelIdentity, null);
        return setCurrentSessionId(scope.getSessionId());
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
        if (currentSessionId == null) throw new IllegalStateException("No active session");
        sessionOperationsService.rewindTo(currentSessionId, afterMessageId);
    }

    public String switchSession(String sessionId) {
        try {
            RuntimeScope scope = sessionResolver.resolveOrCreateSession(principal, channelIdentity, null, sessionId);
            return setCurrentSessionId(scope.getSessionId());
        } catch (SecurityException e) {
            throw new IllegalArgumentException("Session is not accessible: " + sessionId, e);
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

    public Path changeDirectory(String dir) {
        Path newDir = workingDirectory.resolve(dir).normalize();
        if (!java.nio.file.Files.isDirectory(newDir)) {
            throw new IllegalArgumentException("Directory does not exist: " + newDir);
        }
        workingDirectory = newDir;
        return workingDirectory;
    }


    public ChatResult submitPrompt(String message, JsonRpcEventEmitter emitter) {
        return submitPrompt(message, emitter, null);
    }

    public ChatResult submitPrompt(String message, JsonRpcEventEmitter emitter, Consumer<String> sessionReadyHandler) {
        PreparedPrompt prepared = preparePrompt(message, sessionReadyHandler);
        return inboundQueue.enqueue(queueKey, () -> {
            if (!runtimeExecutionService.runCoordinator().isQueueGenerationCurrent(queueKey, prepared.queueGeneration())) {
                return ChatResult.cancelled(prepared.sessionId(), "Queued request superseded");
            }
            return executePrompt(prepared, emitter);
        }, executor).join();
    }

    private synchronized PreparedPrompt preparePrompt(String message, Consumer<String> sessionReadyHandler) {
        if (currentSessionId == null) {
            createSession();
        }
        if (sessionReadyHandler != null) {
            sessionReadyHandler.accept(currentSessionId);
        }
        long queueGeneration = runtimeExecutionService.runCoordinator().currentQueueGeneration(queueKey);
        return new PreparedPrompt(message, currentSessionId, workingDirectory, queueGeneration);
    }

    private synchronized String setCurrentSessionId(String sessionId) {
        this.currentSessionId = sessionId;
        return currentSessionId;
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
            ChatResult result = executionResult.chatResult();
            if (executionResult.scope() != null && result.errorCode() != ChatResult.ErrorCode.CANCELLED) {
                updateCurrentSessionIfStillActive(prepared.sessionId(), executionResult.scope().getSessionId());
            }

            long durationMs = System.currentTimeMillis() - startTime;
            String status = result.success()
                    ? "ok"
                    : (result.errorCode() == ChatResult.ErrorCode.CANCELLED ? "cancelled" : "error");
            String errorText = result.success() || result.errorCode() == ChatResult.ErrorCode.CANCELLED
                    ? null
                    : result.errorMessage();
            emitter.emitMessageComplete(
                    result.content(),
                    status,
                    durationMs,
                    result.streamed(),
                    errorText
            );

            return result;
        } catch (Exception e) {
            log.error("TUI chat execution failed", e);
            emitter.emitError(e.getMessage());
            return ChatResult.failure(e.getMessage());
        } finally {
            pendingApprovals.clear();
            pendingClarifications.clear();
        }
    }
    private void updateCurrentSessionIfStillActive(String preparedSessionId, String resultSessionId) {
        if (resultSessionId == null) {
            return;
        }
        synchronized (this) {
            if (Objects.equals(currentSessionId, preparedSessionId)) {
                currentSessionId = resultSessionId;
            }
        }
    }


    public InterruptResult interruptCurrentRun(JsonRpcEventEmitter emitter, String reason) {
        io.github.huskyagent.application.runtime.StopResult result = currentSessionId != null
                ? runtimeExecutionService.interruptSession(currentSessionId, reason != null ? reason : "user_stop")
                : io.github.huskyagent.application.runtime.StopResult.none(null, reason);
        cancelPendingApprovals();
        if (emitter != null) {
            String text = result.hadActiveRun() ? "Stopped current run." : "No active run to stop.";
            emitter.emitStatusUpdate("cancelled", text);
        }
        return new InterruptResult(result.hadActiveRun(), currentSessionId, reason);
    }

    public boolean respondApproval(String requestId, String choice, boolean always) {
        ApprovalWait wait = pendingApprovals.get(requestId);
        if (wait == null) {
            log.warn("Approval request not found: requestId={}, connectionId={}", requestId, connectionId);
            return false;
        }
        wait.setChoice(choice, always);
        wait.latch().countDown();
        return true;
    }

    public boolean respondClarify(String requestId, String answer) {
        ClarifyWait wait = pendingClarifications.get(requestId);
        if (wait == null) {
            log.warn("Clarification request not found: requestId={}, connectionId={}", requestId, connectionId);
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
                log.warn("Approval timed out: requestId={}, tool={}, connectionId={}", requestId, ctx.toolName(), connectionId);
                emitter.emitError("Approval timed out and was automatically rejected");
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
                log.warn("Clarification timed out: requestId={}, connectionId={}", requestId, connectionId);
                emitter.emitError("Clarification timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pendingClarifications.remove(requestId);
        }

        ctx.respond(wait.answer());
    }


    private record PreparedPrompt(String message, String sessionId, Path workingDirectory, long queueGeneration) {}

    public record InterruptResult(boolean interrupted, String sessionId, String reason) {}

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
