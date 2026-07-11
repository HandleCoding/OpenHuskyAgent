package io.github.huskyagent.application.runtime;

import io.github.huskyagent.application.AgentInput;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelCommand;
import io.github.huskyagent.application.channel.ChannelCommandParser;
import io.github.huskyagent.application.channel.ChannelCommandService;
import io.github.huskyagent.application.channel.binding.ChannelAgentRouter;
import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.channel.runtime.SessionRoute;
import io.github.huskyagent.application.channel.runtime.SessionRouteRegistry;
import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.application.session.ScopedRuntimeContext;
import io.github.huskyagent.application.session.SessionResolver;
import io.github.huskyagent.infra.channel.InboundMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
public class RuntimeExecutionService {

    private final SessionResolver sessionResolver;
    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final ChannelCommandParser commandParser;
    private final ChannelCommandService commandService;
    private final SessionRouteRegistry routeRegistry;
    private final ChannelAgentRouter agentRouter;
    private final ScopedRuntimeContext scopedRuntimeContext;
    private final SessionRunCoordinator runCoordinator;

    @Autowired
    public RuntimeExecutionService(SessionResolver sessionResolver,
                                   AgentRuntimeExecutor agentRuntimeExecutor,
                                   ChannelCommandParser commandParser,
                                   ChannelCommandService commandService,
                                   SessionRouteRegistry routeRegistry,
                                   ChannelAgentRouter agentRouter,
                                   ScopedRuntimeContext scopedRuntimeContext,
                                   SessionRunCoordinator runCoordinator) {
        this.sessionResolver = sessionResolver;
        this.agentRuntimeExecutor = agentRuntimeExecutor;
        this.commandParser = commandParser;
        this.commandService = commandService;
        this.routeRegistry = routeRegistry;
        this.agentRouter = agentRouter;
        this.scopedRuntimeContext = scopedRuntimeContext;
        this.runCoordinator = runCoordinator;
    }

    public RuntimeExecutionService(SessionResolver sessionResolver,
                                   AgentRuntimeExecutor agentRuntimeExecutor,
                                   ChannelCommandParser commandParser,
                                   ChannelCommandService commandService,
                                   SessionRouteRegistry routeRegistry,
                                   ChannelAgentRouter agentRouter,
                                   ScopedRuntimeContext scopedRuntimeContext) {
        this(sessionResolver, agentRuntimeExecutor, commandParser, commandService, routeRegistry,
                agentRouter, scopedRuntimeContext, new SessionRunCoordinator());
    }

    public StopResult interruptSession(String sessionId, String reason) {
        return runCoordinator.interrupt(sessionId, reason);
    }

    public StopResult expireSessionRun(String sessionId, String reason) {
        return runCoordinator.expire(sessionId, reason);
    }

    public SessionRunCoordinator runCoordinator() {
        return runCoordinator;
    }

    public RuntimeExecutionResult execute(RuntimeExecutionRequest request) {
        if (request == null) {
            return RuntimeExecutionResult.rejected(ChatResult.failure("Missing runtime execution request", ChatResult.ErrorCode.PARAM_ERROR));
        }
        InboundMessage inbound = requireInbound(request);
        if (inbound.isIgnored()) {
            return RuntimeExecutionResult.rejected(ChatResult.failure("Ignored inbound message", ChatResult.ErrorCode.PARAM_ERROR));
        }
        AgentInput agentInput = request.getAgentInput() != null
                ? request.getAgentInput()
                : AgentInput.fromInbound(inbound);
        if (!agentInput.hasContent()) {
            return RuntimeExecutionResult.rejected(ChatResult.failure("Empty inbound message", ChatResult.ErrorCode.PARAM_ERROR));
        }

        EffectiveChannelRoute effectiveRoute = request.getEffectiveRoute() != null
                ? request.getEffectiveRoute()
                : agentRouter.resolve(inbound);
        Optional<ChannelCommand> command = request.commandParsingEnabledOrDefault()
                && inbound.getText() != null && !inbound.getText().isBlank()
                ? commandParser.parse(inbound)
                : Optional.empty();
        if (command.isPresent() && commandService.supports(command.get())) {
            return RuntimeExecutionResult.commandHandled(commandService.execute(command.get(), inbound, effectiveRoute.agentId()));
        }

        RuntimeCallbacks callbacks = request.callbacksOrNoop();
        RuntimeScope scope;
        try {
            scope = resolveScope(request, inbound, effectiveRoute.agentId());
        } catch (SecurityException e) {
            return RuntimeExecutionResult.rejected(ChatResult.failure(e.getMessage(), ChatResult.ErrorCode.SESSION_ERROR));
        }
        boolean stateless = request.isStateless();
        if (request.getWorkingDirectoryOverride() != null) {
            scope = scope.withWorkingDirectory(request.getWorkingDirectoryOverride());
        }
        scope.requireCompleteForExecution();

        log.debug("Resolved runtime scene: channel={}, platformAccountId={}, agentId={}, source={}, bindingId={}",
                inbound.getChannelIdentity().getChannelType(), inbound.getChannelIdentity().getPlatformAccountId(),
                effectiveRoute.agentId(), effectiveRoute.source(), effectiveRoute.bindingId());

        SessionRoute route = buildRoute(scope, inbound);
        RunHandle runHandle = request.getRunHandle() != null
                ? request.getRunHandle()
                : runCoordinator.registerStart(scope.getSessionId(), Thread.currentThread());
        RuntimeCallbacks guardedCallbacks = guardedCallbacks(callbacks, runHandle);
        routeRegistry.register(route);
        try {
            guardedCallbacks.started(scope);
            RuntimeScope finalScope = scope;
            AgentInput finalAgentInput = agentInput;
            ChatResult result = scopedRuntimeContext.call(finalScope, () -> agentRuntimeExecutor.execute(
                    finalScope,
                    finalAgentInput,
                    guardedCallbacks,
                    request.persistenceModeOrDefault(),
                    runHandle
            ));
            if (!runCoordinator.isCurrent(runHandle)) {
                return RuntimeExecutionResult.executed(ChatResult.cancelled(finalScope.getSessionId(), "Run cancelled"), finalScope);
            }
            if (result.success()) {
                guardedCallbacks.completed(finalScope, result);
            } else if (result.errorCode() != ChatResult.ErrorCode.CANCELLED) {
                guardedCallbacks.failed(finalScope, result.errorMessage());
            }
            if (!runCoordinator.isCurrent(runHandle)) {
                return RuntimeExecutionResult.executed(ChatResult.cancelled(finalScope.getSessionId(), "Run cancelled"), finalScope);
            }
            return RuntimeExecutionResult.executed(result, finalScope);
        } catch (RunCancelledException e) {
            return RuntimeExecutionResult.executed(ChatResult.cancelled(scope.getSessionId(), e.getMessage()), scope);
        } catch (Exception e) {
            if (!runCoordinator.isCurrent(runHandle)) {
                return RuntimeExecutionResult.executed(ChatResult.cancelled(scope.getSessionId(), "Run cancelled"), scope);
            }
            log.error("Runtime execution failed: channel={}, sessionId={}",
                    inbound.getChannelIdentity().getChannelType(), scope.getSessionId(), e);
            callbacks.failed(scope, e.getMessage());
            return RuntimeExecutionResult.executed(ChatResult.failure(e.getMessage()), scope);
        } finally {
            runCoordinator.finishIfCurrent(runHandle);
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

    private RuntimeScope resolveScope(RuntimeExecutionRequest request, InboundMessage inbound, String agentId) {
        String requestedSessionId = request.requestedSessionIdOrInbound();
        if (request.isStateless() && (requestedSessionId == null || requestedSessionId.isBlank())) {
            return sessionResolver.createEphemeralScope(inbound.getPrincipal(), inbound.getChannelIdentity(), agentId);
        }
        if (request.isForceNewSession() && (requestedSessionId == null || requestedSessionId.isBlank())) {
            return sessionResolver.createSession(inbound.getPrincipal(), inbound.getChannelIdentity(), agentId);
        }
        return sessionResolver.resolveOrCreateSession(
                inbound.getPrincipal(),
                inbound.getChannelIdentity(),
                agentId,
                requestedSessionId
        );
    }

    private RuntimeCallbacks guardedCallbacks(RuntimeCallbacks delegate, RunHandle handle) {
        RuntimeCallbacks target = delegate != null ? delegate : RuntimeCallbacks.NOOP;
        return new RuntimeCallbacks() {
            @Override
            public void started(RuntimeScope scope) {
                if (runCoordinator.isCurrent(handle)) {
                    target.started(scope);
                }
            }

            @Override
            public void text(RuntimeScope scope, io.github.huskyagent.application.agent.TextEvent event) {
                if (runCoordinator.isCurrent(handle)) {
                    target.text(scope, event);
                }
            }

            @Override
            public void approval(RuntimeScope scope, io.github.huskyagent.application.agent.ApprovalContext approval) {
                if (runCoordinator.isCurrent(handle)) {
                    target.approval(scope, approval);
                } else {
                    approval.approve(false, false);
                }
            }

            @Override
            public void clarify(RuntimeScope scope, io.github.huskyagent.application.agent.ClarifyContext clarify) {
                if (runCoordinator.isCurrent(handle)) {
                    target.clarify(scope, clarify);
                } else {
                    clarify.respond("");
                }
            }

            @Override
            public void completed(RuntimeScope scope, ChatResult result) {
                if (runCoordinator.isCurrent(handle)) {
                    target.completed(scope, result);
                }
            }

            @Override
            public void failed(RuntimeScope scope, String errorMessage) {
                if (runCoordinator.isCurrent(handle)) {
                    target.failed(scope, errorMessage);
                }
            }
        };
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
