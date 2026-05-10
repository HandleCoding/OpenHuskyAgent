package io.github.huskyagent.application.runtime;

import io.github.huskyagent.application.AgentInput;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelCommand;
import io.github.huskyagent.application.channel.ChannelCommandParser;
import io.github.huskyagent.application.channel.ChannelCommandService;
import io.github.huskyagent.application.channel.binding.ChannelSceneRouter;
import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.channel.runtime.SessionRoute;
import io.github.huskyagent.application.channel.runtime.SessionRouteRegistry;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.application.session.ScopedRuntimeContext;
import io.github.huskyagent.application.session.SessionResolver;
import io.github.huskyagent.infra.channel.InboundMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeExecutionService {

    private final SessionResolver sessionResolver;
    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final ChannelCommandParser commandParser;
    private final ChannelCommandService commandService;
    private final SessionRouteRegistry routeRegistry;
    private final ChannelSceneRouter sceneRouter;
    private final ScopedRuntimeContext scopedRuntimeContext;

    public RuntimeExecutionResult execute(RuntimeExecutionRequest request) {
        if (request == null) {
            return RuntimeExecutionResult.rejected(ChatResult.failure("Missing runtime execution request", ChatResult.ErrorCode.PARAM_ERROR));
        }
        InboundMessage inbound = requireInbound(request);
        if (inbound.isIgnored()) {
            return RuntimeExecutionResult.rejected(ChatResult.failure("Ignored inbound message", ChatResult.ErrorCode.PARAM_ERROR));
        }
        if (!inbound.hasContent() && (request.messageOrInboundText() == null || request.messageOrInboundText().isBlank())) {
            return RuntimeExecutionResult.rejected(ChatResult.failure("Empty inbound message", ChatResult.ErrorCode.PARAM_ERROR));
        }

        EffectiveChannelRoute effectiveRoute = request.getEffectiveRoute() != null
                ? request.getEffectiveRoute()
                : sceneRouter.resolve(inbound);
        Optional<ChannelCommand> command = inbound.getText() != null && !inbound.getText().isBlank()
                ? commandParser.parse(inbound)
                : Optional.empty();
        if (command.isPresent() && commandService.supports(command.get())) {
            return RuntimeExecutionResult.commandHandled(commandService.execute(command.get(), inbound, effectiveRoute.sceneId()));
        }

        RuntimeCallbacks callbacks = request.callbacksOrNoop();
        RuntimeScope scope;
        try {
            scope = resolveScope(request, inbound, effectiveRoute.sceneId());
        } catch (SecurityException e) {
            return RuntimeExecutionResult.rejected(ChatResult.failure(e.getMessage(), ChatResult.ErrorCode.SESSION_ERROR));
        }
        boolean stateless = request.isStateless();
        if (request.getWorkingDirectoryOverride() != null) {
            scope = scope.withWorkingDirectory(request.getWorkingDirectoryOverride());
        }
        scope.requireCompleteForExecution();

        log.debug("Resolved runtime scene: channel={}, platformAccountId={}, sceneId={}, source={}, bindingId={}",
                inbound.getChannelIdentity().getChannelType(), inbound.getChannelIdentity().getPlatformAccountId(),
                effectiveRoute.sceneId(), effectiveRoute.source(), effectiveRoute.bindingId());

        SessionRoute route = buildRoute(scope, inbound);
        routeRegistry.register(route);
        try {
            callbacks.started(scope);
            RuntimeScope finalScope = scope;
            ChatResult result = scopedRuntimeContext.call(finalScope, () -> agentRuntimeExecutor.execute(
                    finalScope,
                    AgentInput.fromInbound(inbound),
                    callbacks,
                    request.persistenceModeOrDefault()
            ));
            if (result.success()) {
                callbacks.completed(finalScope, result);
            } else {
                callbacks.failed(finalScope, result.errorMessage());
            }
            return RuntimeExecutionResult.executed(result, finalScope);
        } catch (Exception e) {
            log.error("Runtime execution failed: channel={}, sessionId={}",
                    inbound.getChannelIdentity().getChannelType(), scope.getSessionId(), e);
            callbacks.failed(scope, e.getMessage());
            return RuntimeExecutionResult.executed(ChatResult.failure(e.getMessage()), scope);
        } finally {
            routeRegistry.unregister(route);
            if (stateless) {
                sessionResolver.releaseEphemeralScope(scope);
            }
        }
    }

    private InboundMessage requireInbound(RuntimeExecutionRequest request) {
        InboundMessage inbound = request.getInbound();
        if (inbound == null) {
            throw new IllegalArgumentException("inbound is required");
        }
        if (inbound.getPrincipal() == null) {
            throw new IllegalArgumentException("inbound principal is required");
        }
        if (inbound.getChannelIdentity() == null) {
            throw new IllegalArgumentException("inbound channelIdentity is required");
        }
        return inbound;
    }

    private RuntimeScope resolveScope(RuntimeExecutionRequest request, InboundMessage inbound, String sceneId) {
        String requestedSessionId = request.requestedSessionIdOrInbound();
        if (request.isStateless() && (requestedSessionId == null || requestedSessionId.isBlank())) {
            return sessionResolver.createEphemeralScope(inbound.getPrincipal(), inbound.getChannelIdentity(), sceneId);
        }
        if (request.isForceNewSession() && (requestedSessionId == null || requestedSessionId.isBlank())) {
            return sessionResolver.createSession(inbound.getPrincipal(), inbound.getChannelIdentity(), sceneId);
        }
        return sessionResolver.resolveOrCreateSession(
                inbound.getPrincipal(),
                inbound.getChannelIdentity(),
                sceneId,
                requestedSessionId
        );
    }

    private SessionRoute buildRoute(RuntimeScope scope, InboundMessage inbound) {
        return new SessionRoute(
                scope.getSessionId(),
                inbound.getChannelIdentity().getChannelType(),
                inbound.getChannelIdentity(),
                inbound.getReplyTarget(),
                inbound.getMessageId()
        );
    }
}
