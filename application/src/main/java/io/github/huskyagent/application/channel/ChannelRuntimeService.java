package io.github.huskyagent.application.channel;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.binding.ChannelSceneRouter;
import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.runtime.RuntimeExecutionRequest;
import io.github.huskyagent.application.runtime.RuntimeExecutionResult;
import io.github.huskyagent.application.runtime.RuntimeExecutionService;
import io.github.huskyagent.application.runtime.StopResult;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.application.session.SessionResolver;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.OutboundMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class ChannelRuntimeService {

    private final RuntimeExecutionService runtimeExecutionService;
    private final ChannelInboundQueue inboundQueue;
    private final ChannelRuntimeQueueKeyFactory queueKeyFactory;
    private final ChannelSceneRouter sceneRouter;
    private final ChannelCommandParser commandParser;
    private final BypassCommandPolicy bypassCommandPolicy;
    private final SessionResolver sessionResolver;

    @Autowired
    public ChannelRuntimeService(RuntimeExecutionService runtimeExecutionService,
                                 ChannelInboundQueue inboundQueue,
                                 ChannelRuntimeQueueKeyFactory queueKeyFactory,
                                 ChannelSceneRouter sceneRouter,
                                 ChannelCommandParser commandParser,
                                 BypassCommandPolicy bypassCommandPolicy,
                                 SessionResolver sessionResolver) {
        this.runtimeExecutionService = runtimeExecutionService;
        this.inboundQueue = inboundQueue;
        this.queueKeyFactory = queueKeyFactory;
        this.sceneRouter = sceneRouter;
        this.commandParser = commandParser;
        this.bypassCommandPolicy = bypassCommandPolicy;
        this.sessionResolver = sessionResolver;
    }

    public ChannelRuntimeService(RuntimeExecutionService runtimeExecutionService,
                                 ChannelInboundQueue inboundQueue,
                                 ChannelRuntimeQueueKeyFactory queueKeyFactory,
                                 ChannelSceneRouter sceneRouter) {
        this(runtimeExecutionService, inboundQueue, queueKeyFactory, sceneRouter,
                inbound -> Optional.empty(), new BypassCommandPolicy(), null);
    }

    public CompletableFuture<ChatResult> handleInboundAsync(InboundMessage inbound, ChannelAdapter adapter, Executor executor) {
        EffectiveChannelRoute route = sceneRouter.resolve(inbound);
        String queueKey = queueKeyFactory.keyFor(inbound, route);
        Optional<ChannelCommand> command = parseCommand(inbound);
        CommandExecutionMode mode = command.map(bypassCommandPolicy::modeFor).orElse(CommandExecutionMode.NORMAL_QUEUED);
        if (mode != CommandExecutionMode.NORMAL_QUEUED) {
            try {
                return CompletableFuture.completedFuture(handleBypass(command.orElseThrow(), mode, inbound, adapter, route, queueKey));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(ChatResult.failure(e.getMessage()));
            }
        }
        long generation = runtimeExecutionService.runCoordinator().currentQueueGeneration(queueKey);
        return inboundQueue.enqueue(queueKey, () -> {
            if (!runtimeExecutionService.runCoordinator().isQueueGenerationCurrent(queueKey, generation)) {
                return ChatResult.cancelled(inbound.getRequestedSessionId(), "Queued request superseded");
            }
            return handleInbound(inbound, adapter, route);
        }, executor);
    }

    public ChatResult handleInbound(InboundMessage inbound, ChannelAdapter adapter) {
        EffectiveChannelRoute route = sceneRouter.resolve(inbound);
        return handleInbound(inbound, adapter, route);
    }

    private Optional<ChannelCommand> parseCommand(InboundMessage inbound) {
        return inbound != null && inbound.getText() != null && !inbound.getText().isBlank()
                ? commandParser.parse(inbound)
                : Optional.empty();
    }

    private ChatResult handleBypass(ChannelCommand command, CommandExecutionMode mode, InboundMessage inbound,
                                    ChannelAdapter adapter, EffectiveChannelRoute route, String queueKey) {
        return switch (mode) {
            case BYPASS_CANCEL_ACTIVE -> stopActiveRun(inbound, adapter, route);
            case BYPASS_REPLACE_ACTIVE_AND_PENDING -> newSession(inbound, adapter, route, queueKey);
            case NORMAL_QUEUED -> handleInbound(inbound, adapter, route);
        };
    }

    private ChatResult stopActiveRun(InboundMessage inbound, ChannelAdapter adapter, EffectiveChannelRoute route) {
        Optional<String> sessionId = activeSessionId(inbound, route);
        StopResult stopResult = sessionId
                .map(id -> runtimeExecutionService.interruptSession(id, "channel_stop"))
                .orElse(StopResult.none(null, "channel_stop"));
        String text = stopResult.hadActiveRun()
                ? "Stopped current run."
                : "No active run to stop.";
        adapter.send(reply(inbound, sessionId.orElse(null), text));
        return ChatResult.success(text, sessionId.orElse(null), false);
    }

    private ChatResult newSession(InboundMessage inbound, ChannelAdapter adapter, EffectiveChannelRoute route, String queueKey) {
        runtimeExecutionService.runCoordinator().bumpQueueGeneration(queueKey);
        activeSessionId(inbound, route)
                .ifPresent(id -> runtimeExecutionService.expireSessionRun(id, "channel_new_session"));
        if (sessionResolver == null) {
            ChatResult result = ChatResult.failure("Session resolver is not available");
            adapter.send(reply(inbound, null, result.errorMessage()));
            return result;
        }
        RuntimeScope scope = sessionResolver.createSession(inbound.getPrincipal(), inbound.getChannelIdentity(), route.sceneId());
        String text = "Created new session: " + scope.getSessionId();
        adapter.send(reply(inbound, scope.getSessionId(), text));
        return ChatResult.success(text, scope.getSessionId(), false);
    }

    private Optional<String> activeSessionId(InboundMessage inbound, EffectiveChannelRoute route) {
        if (inbound.getRequestedSessionId() != null && !inbound.getRequestedSessionId().isBlank()) {
            return Optional.of(inbound.getRequestedSessionId());
        }
        return sessionResolver != null
                ? sessionResolver.findActiveSessionId(inbound.getPrincipal(), inbound.getChannelIdentity(), route.sceneId())
                : Optional.empty();
    }

    private OutboundMessage reply(InboundMessage inbound, String sessionId, String text) {
        return OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TEXT)
                .sessionId(sessionId)
                .channelIdentity(inbound.getChannelIdentity())
                .replyTarget(inbound.getReplyTarget())
                .text(text)
                .build();
    }

    private ChatResult handleInbound(InboundMessage inbound, ChannelAdapter adapter, EffectiveChannelRoute route) {
        RuntimeExecutionResult result = runtimeExecutionService.execute(RuntimeExecutionRequest.builder()
                .inbound(inbound)
                .effectiveRoute(route)
                .callbacks(new AdapterCallbacks(adapter, inbound))
                .build());
        if (result.commandHandled() && result.commandReply() != null) {
            adapter.send(result.commandReply());
        }
        return result.chatResult();
    }
}