package io.github.huskyagent.service.openai;

import io.github.huskyagent.application.AgentInput;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelInboundQueue;
import io.github.huskyagent.application.channel.ChannelRuntimeQueueKeyFactory;
import io.github.huskyagent.application.channel.binding.ChannelAgentRouter;
import io.github.huskyagent.application.channel.binding.EffectiveChannelRoute;
import io.github.huskyagent.application.runtime.RuntimeExecutionRequest;
import io.github.huskyagent.application.runtime.RuntimeExecutionResult;
import io.github.huskyagent.application.runtime.RuntimeExecutionService;
import io.github.huskyagent.infra.auth.PrincipalContext;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Service
class OpenAiCompatibleRuntimeService {

    private final RuntimeExecutionService runtimeExecutionService;
    private final ChannelInboundQueue inboundQueue;
    private final ChannelRuntimeQueueKeyFactory queueKeyFactory;
    private final ChannelAgentRouter agentRouter;
    private final OpenAiCompatibleProperties properties;
    private final OpenAiModelCatalog modelCatalog;
    private final OpenAiPromptMapper promptMapper;
    private final OpenAiChannelAdapter channelAdapter;
    private final Executor agentExecutor;

    OpenAiCompatibleRuntimeService(RuntimeExecutionService runtimeExecutionService,
                                   ChannelInboundQueue inboundQueue,
                                   ChannelRuntimeQueueKeyFactory queueKeyFactory,
                                   ChannelAgentRouter agentRouter,
                                   OpenAiCompatibleProperties properties,
                                   OpenAiModelCatalog modelCatalog,
                                   OpenAiPromptMapper promptMapper,
                                   OpenAiChannelAdapter channelAdapter,
                                   @Qualifier("agentExecutor") Executor agentExecutor) {
        this.runtimeExecutionService = runtimeExecutionService;
        this.inboundQueue = inboundQueue;
        this.queueKeyFactory = queueKeyFactory;
        this.agentRouter = agentRouter;
        this.properties = properties;
        this.modelCatalog = modelCatalog;
        this.promptMapper = promptMapper;
        this.channelAdapter = channelAdapter;
        this.agentExecutor = agentExecutor;
    }

    RuntimeExecutionResult execute(OpenAiChatCompletionRequest request, String sessionId,
                                   OpenAiCollectingRuntimeCallbacks callbacks) {
        RuntimeContext context = buildContext(request, sessionId);
        CompletableFuture<RuntimeExecutionResult> future = inboundQueue.enqueue(
                        queueKey(context),
                        () -> executeNow(context, callbacks).chatResult(),
                        agentExecutor
                ).thenApply(ignored -> context.result())
                .exceptionally(error -> RuntimeExecutionResult.rejected(ChatResult.failure(errorMessage(error))));
        return future.join();
    }

    void stream(OpenAiChatCompletionRequest request, String sessionId, OpenAiStreamingRuntimeCallbacks callbacks) {
        RuntimeContext context = buildContext(request, sessionId);
        callbacks.registerWith(channelAdapter);
        inboundQueue.enqueue(queueKey(context), () -> {
            try {
                RuntimeExecutionResult result = executeNow(context, callbacks);
                handleStreamingResult(result, callbacks);
                return result.chatResult();
            } finally {
                channelAdapter.unregister(callbacks.registeredSessionId());
            }
        }, agentExecutor).exceptionally(error -> {
            channelAdapter.unregister(callbacks.registeredSessionId());
            callbacks.failed(null, error.getMessage());
            return null;
        });
    }

    OpenAiWireResponses.ModelsResponse models() {
        return modelCatalog.models();
    }

    private RuntimeContext buildContext(OpenAiChatCompletionRequest request, String sessionId) {
        String agentId = modelCatalog.resolveAgentId(request.model());
        OpenAiPromptMapper.MappedPrompt mappedPrompt = promptMapper.map(request);
        InboundMessage inbound = buildInbound(request, agentId, mappedPrompt.displayText(), sessionId);
        EffectiveChannelRoute route = agentRouter.resolve(inbound);
        AgentInput agentInput = AgentInput.structuredMessages(mappedPrompt.messages(), mappedPrompt.displayText());
        return new RuntimeContext(inbound, route, agentInput, normalize(sessionId) != null);
    }

    private RuntimeExecutionResult executeNow(RuntimeContext context, io.github.huskyagent.application.runtime.RuntimeCallbacks callbacks) {
        RuntimeExecutionResult result = runtimeExecutionService.execute(RuntimeExecutionRequest.builder()
                .inbound(context.inbound())
                .effectiveRoute(context.route())
                .forceNewSession(false)
                .persistenceMode(context.persistenceMode())
                .agentInput(context.agentInput())
                .commandParsingEnabled(false)
                .callbacks(callbacks)
                .build());
        context.result(result);
        return result;
    }

    private String queueKey(RuntimeContext context) {
        if (context.stateless()) {
            return "openai-stateless:" + context.inbound().getMessageId();
        }
        return queueKeyFactory.keyFor(context.inbound(), context.route());
    }

    private void handleStreamingResult(RuntimeExecutionResult result, OpenAiStreamingRuntimeCallbacks callbacks) {
        ChatResult chatResult = result != null ? result.chatResult() : null;
        if (chatResult == null) {
            callbacks.failed(result != null ? result.scope() : null, "Runtime execution failed");
            return;
        }
        if (!chatResult.success()) {
            callbacks.failed(result.scope(), chatResult.errorMessage(), chatResult.errorCode());
            return;
        }
        if (result.scope() == null) {
            callbacks.emitContent(chatResult.content());
            callbacks.finish();
        }
    }

    private InboundMessage buildInbound(OpenAiChatCompletionRequest request, String agentId, String prompt, String sessionId) {
        Principal principal = principal(request);
        ChannelIdentity channelIdentity = ChannelIdentity.builder()
                .channelType(ChannelType.HTTP)
                .conversationType(ConversationType.DIRECT)
                .platformAccountId(properties.getPlatformAccountId())
                .senderId(principal.getId())
                .build();
        return InboundMessage.builder()
                .messageId("openai-" + UUID.randomUUID())
                .text(prompt)
                .requestedSessionId(normalize(sessionId))
                .principal(principal)
                .channelIdentity(channelIdentity)
                .agentId(agentId)
                .rawPayload(request)
                .metadata(metadata(request, agentId))
                .build();
    }

    private Principal principal(OpenAiChatCompletionRequest request) {
        Principal current = PrincipalContext.get();
        if (current != null) {
            return current;
        }
        String userId = normalize(request.user());
        if (userId == null) {
            userId = properties.getDefaultUserId();
        }
        return Principal.builder()
                .id("api:" + userId)
                .displayName(userId)
                .channelType(ChannelType.HTTP)
                .build();
    }

    private Map<String, Object> metadata(OpenAiChatCompletionRequest request, String agentId) {
        Map<String, Object> metadata = new HashMap<>();
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        metadata.put("openai.model", request.model());
        metadata.put("openai.agentId", agentId);
        metadata.put("openai.stream", request.streamEnabled());
        return metadata;
    }

    private String errorMessage(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current != null && current.getMessage() != null ? current.getMessage() : "Runtime execution failed";
    }

    private String normalize(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    private static final class RuntimeContext {
        private final InboundMessage inbound;
        private final EffectiveChannelRoute route;
        private final AgentInput agentInput;
        private final boolean stateful;
        private RuntimeExecutionResult result;

        private RuntimeContext(InboundMessage inbound, EffectiveChannelRoute route, AgentInput agentInput, boolean stateful) {
            this.inbound = inbound;
            this.route = route;
            this.agentInput = agentInput;
            this.stateful = stateful;
        }

        InboundMessage inbound() {
            return inbound;
        }

        EffectiveChannelRoute route() {
            return route;
        }

        AgentInput agentInput() {
            return agentInput;
        }

        boolean stateful() {
            return stateful;
        }

        boolean stateless() {
            return !stateful;
        }

        RuntimeExecutionRequest.PersistenceMode persistenceMode() {
            return stateful
                    ? RuntimeExecutionRequest.PersistenceMode.STATEFUL
                    : RuntimeExecutionRequest.PersistenceMode.STATELESS;
        }

        RuntimeExecutionResult result() {
            return result;
        }

        void result(RuntimeExecutionResult result) {
            this.result = result;
        }
    }
}
