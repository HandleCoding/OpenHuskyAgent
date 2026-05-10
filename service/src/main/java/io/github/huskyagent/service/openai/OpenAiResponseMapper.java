package io.github.huskyagent.service.openai;

import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.infra.context.TokenUsage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class OpenAiResponseMapper {

    String newCompletionId() {
        return "chatcmpl-" + UUID.randomUUID();
    }

    long created() {
        return Instant.now().getEpochSecond();
    }

    OpenAiWireResponses.ChatCompletion success(String id, long created, String model, String content, TokenUsage tokenUsage) {
        OpenAiWireResponses.Message message = new OpenAiWireResponses.Message("assistant", content != null ? content : "");
        OpenAiWireResponses.Choice choice = new OpenAiWireResponses.Choice(0, message, "stop", null);
        return new OpenAiWireResponses.ChatCompletion(id, "chat.completion", created, model, List.of(choice), usage(tokenUsage));
    }

    OpenAiWireResponses.ChatCompletionChunk roleChunk(String id, long created, String model) {
        return chunk(id, created, model, new OpenAiWireResponses.Delta("assistant", null), null);
    }

    OpenAiWireResponses.ChatCompletionChunk contentChunk(String id, long created, String model, String token) {
        return chunk(id, created, model, new OpenAiWireResponses.Delta(null, token), null);
    }

    OpenAiWireResponses.ChatCompletionChunk stopChunk(String id, long created, String model) {
        return chunk(id, created, model, new OpenAiWireResponses.Delta(null, null), "stop");
    }

    ResponseEntity<OpenAiWireResponses.ErrorResponse> protocolError(OpenAiProtocolException error) {
        return error(HttpStatus.BAD_REQUEST, error.getMessage(), "invalid_request_error", error.getParam(), error.getCode());
    }

    ResponseEntity<OpenAiWireResponses.ErrorResponse> runtimeError(ChatResult result) {
        HttpStatus status = result != null && result.errorCode() == ChatResult.ErrorCode.PARAM_ERROR
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.INTERNAL_SERVER_ERROR;
        String code = result != null && result.errorCode() != null ? result.errorCode().name().toLowerCase() : "internal_error";
        String message = result != null && result.errorMessage() != null ? result.errorMessage() : "Runtime execution failed";
        return error(status, message, status == HttpStatus.BAD_REQUEST ? "invalid_request_error" : "server_error", null, code);
    }

    ResponseEntity<OpenAiWireResponses.ErrorResponse> featureDisabled() {
        return error(HttpStatus.NOT_FOUND, "OpenAI-compatible API is disabled", "invalid_request_error", null, "endpoint_disabled");
    }

    ResponseEntity<OpenAiWireResponses.ErrorResponse> error(HttpStatus status, String message, String type, String param, String code) {
        OpenAiWireResponses.ErrorBody body = new OpenAiWireResponses.ErrorBody(message, type, param, code);
        return ResponseEntity.status(status).body(new OpenAiWireResponses.ErrorResponse(body));
    }

    private OpenAiWireResponses.ChatCompletionChunk chunk(String id, long created, String model,
                                                          OpenAiWireResponses.Delta delta, String finishReason) {
        OpenAiWireResponses.ChunkChoice choice = new OpenAiWireResponses.ChunkChoice(0, delta, finishReason, null);
        return new OpenAiWireResponses.ChatCompletionChunk(id, "chat.completion.chunk", created, model, List.of(choice), null);
    }

    private OpenAiWireResponses.Usage usage(TokenUsage tokenUsage) {
        TokenUsage value = tokenUsage != null ? tokenUsage : TokenUsage.empty();
        return new OpenAiWireResponses.Usage(value.promptTokens(), value.completionTokens(), value.totalTokens());
    }
}
