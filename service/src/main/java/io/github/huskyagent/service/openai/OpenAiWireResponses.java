package io.github.huskyagent.service.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

final class OpenAiWireResponses {

    private OpenAiWireResponses() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatCompletion(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatCompletionChunk(
            String id,
            String object,
            long created,
            String model,
            List<ChunkChoice> choices,
            Usage usage) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Choice(
            int index,
            Message message,
            @JsonProperty("finish_reason") String finishReason,
            Object logprobs) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChunkChoice(
            int index,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason,
            Object logprobs) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Message(
            String role,
            String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Delta(
            String role,
            String content) {
    }

    record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {
    }

    record ModelsResponse(
            String object,
            List<ModelObject> data) {
    }

    record ModelObject(
            String id,
            String object,
            long created,
            @JsonProperty("owned_by") String ownedBy) {
    }

    record ErrorResponse(ErrorBody error) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorBody(
            String message,
            String type,
            String param,
            String code) {
    }
}
