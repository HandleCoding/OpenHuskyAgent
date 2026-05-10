package io.github.huskyagent.service.openai;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.runtime.RuntimeExecutionResult;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class OpenAiCompatibleController {

    private final OpenAiCompatibleProperties properties;
    private final OpenAiCompatibleRuntimeService runtimeService;
    private final OpenAiResponseMapper responseMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    OpenAiCompatibleController(OpenAiCompatibleProperties properties,
                               OpenAiCompatibleRuntimeService runtimeService,
                               OpenAiResponseMapper responseMapper,
                               com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.properties = properties;
        this.runtimeService = runtimeService;
        this.responseMapper = responseMapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/v1/chat/completions", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    Object chatCompletions(@RequestBody OpenAiChatCompletionRequest request,
                           @RequestHeader(value = "X-Husky-Session-Id", required = false) String headerSessionId,
                           HttpServletResponse servletResponse) {
        if (!properties.isEnabled()) {
            return responseMapper.featureDisabled();
        }
        try {
            String sessionId = sessionId(request, headerSessionId);
            boolean stateful = sessionId != null;
            String completionId = responseMapper.newCompletionId();
            long created = responseMapper.created();
            if (request.streamEnabled()) {
                if (stateful) {
                    servletResponse.setHeader("X-Husky-Session-Id", sessionId);
                }
                SseEmitter emitter = new SseEmitter(properties.getStreamTimeoutMs());
                OpenAiStreamingRuntimeCallbacks callbacks = new OpenAiStreamingRuntimeCallbacks(
                        emitter, objectMapper, responseMapper, completionId, created, request.model());
                runtimeService.stream(request, sessionId, callbacks);
                return emitter;
            }
            OpenAiCollectingRuntimeCallbacks callbacks = new OpenAiCollectingRuntimeCallbacks();
            RuntimeExecutionResult result = runtimeService.execute(request, sessionId, callbacks);
            ChatResult chatResult = result.chatResult();
            if (chatResult == null || !chatResult.success()) {
                return responseMapper.runtimeError(chatResult);
            }
            String resolvedSessionId = result.sessionId();
            if (stateful && resolvedSessionId != null) {
                servletResponse.setHeader("X-Husky-Session-Id", resolvedSessionId);
            }
            return ResponseEntity.ok(responseMapper.success(
                    completionId,
                    created,
                    request.model(),
                    callbacks.content(chatResult),
                    chatResult.tokenUsage()));
        } catch (OpenAiProtocolException e) {
            return responseMapper.protocolError(e);
        }
    }

    @GetMapping(path = "/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
    Object models() {
        if (!properties.isEnabled()) {
            return responseMapper.featureDisabled();
        }
        return runtimeService.models();
    }

    private String sessionId(OpenAiChatCompletionRequest request, String headerSessionId) {
        String normalized = normalize(headerSessionId);
        if (normalized != null) {
            return normalized;
        }
        if (request != null && request.metadata() != null) {
            Object metadataSession = request.metadata().get("session_id");
            if (metadataSession instanceof String value) {
                return normalize(value);
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value != null && !value.isBlank() ? value : null;
    }
}
