package io.github.huskyagent.service.controller;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelInboundQueue;
import io.github.huskyagent.application.channel.ChannelRuntimeQueueKeyFactory;
import io.github.huskyagent.application.channel.binding.ChannelSceneRouter;
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
import io.github.huskyagent.infra.chatbot.ChatbotConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class SseChatController {

    private final ChatbotConfig chatbotConfig;
    private final SseChannelAdapter sseChannelAdapter;
    private final RuntimeExecutionService runtimeExecutionService;
    private final ChannelInboundQueue inboundQueue;
    private final ChannelRuntimeQueueKeyFactory queueKeyFactory;
    private final ChannelSceneRouter sceneRouter;
    private final Executor agentExecutor;

    public SseChatController(ChatbotConfig chatbotConfig,
                             SseChannelAdapter sseChannelAdapter,
                             RuntimeExecutionService runtimeExecutionService,
                             ChannelInboundQueue inboundQueue,
                             ChannelRuntimeQueueKeyFactory queueKeyFactory,
                             ChannelSceneRouter sceneRouter,
                             @Qualifier("agentExecutor") Executor agentExecutor) {
        this.chatbotConfig = chatbotConfig;
        this.sseChannelAdapter = sseChannelAdapter;
        this.runtimeExecutionService = runtimeExecutionService;
        this.inboundQueue = inboundQueue;
        this.queueKeyFactory = queueKeyFactory;
        this.sceneRouter = sceneRouter;
        this.agentExecutor = agentExecutor;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request,
                           @RequestHeader(value = "X-Scene", required = false) String sceneId) {
        if (!chatbotConfig.isEnabled()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"Chatbot mode disabled\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        String sessionId = normalizeSessionId(request.sessionId());
        SseEmitter emitter = new SseEmitter(300_000L);
        SseRuntimeCallbacks callbacks = new SseRuntimeCallbacks(emitter, sseChannelAdapter);
        InboundMessage inbound = buildInbound(request, sessionId, sceneId);
        EffectiveChannelRoute route = sceneRouter.resolve(inbound);
        emitter.onTimeout(() -> log.warn("SSE emitter timeout: requestedSessionId={}", sessionId));

        inboundQueue.enqueue(queueKeyFactory.keyFor(inbound, route), () -> {
            try {
                RuntimeExecutionResult result = runtimeExecutionService.execute(RuntimeExecutionRequest.builder()
                        .inbound(inbound)
                        .effectiveRoute(route)
                        .forceNewSession(sessionId == null)
                        .callbacks(callbacks)
                        .build());
                handleEarlyResult(emitter, result);
                emitter.complete();
                return result.chatResult();
            } catch (Exception e) {
                try {
                    SseEventMapper.sendErrorEvent(emitter, e.getMessage(), ChatResult.ErrorCode.INTERNAL_ERROR);
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
                throw e;
            } finally {
                callbacks.unregister();
            }
        }, agentExecutor).exceptionally(error -> null);

        return emitter;
    }

    private void handleEarlyResult(SseEmitter emitter, RuntimeExecutionResult result) {
        if (result == null || result.scope() != null) {
            return;
        }
        ChatResult chatResult = result.chatResult();
        if (chatResult == null) {
            return;
        }
        if (chatResult.success()) {
            SseEventMapper.sendDoneEvent(emitter, chatResult);
        } else {
            SseEventMapper.sendErrorEvent(emitter, chatResult.errorMessage(), chatResult.errorCode());
        }
    }

    private InboundMessage buildInbound(ChatRequest request, String sessionId, String sceneId) {
        Principal principal = PrincipalContext.get();
        String userId = principal.getId();
        ChannelIdentity channelIdentity = ChannelIdentity.builder()
                .channelType(ChannelType.HTTP)
                .conversationType(ConversationType.DIRECT)
                .platformAccountId("chatbot")
                .senderId(userId)
                .build();
        return InboundMessage.builder()
                .text(request.message())
                .requestedSessionId(sessionId)
                .principal(principal)
                .channelIdentity(channelIdentity)
                .sceneId(sceneId)
                .build();
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId != null && !sessionId.isBlank() ? sessionId : null;
    }

    public record ChatRequest(String message, String sessionId) {}
}