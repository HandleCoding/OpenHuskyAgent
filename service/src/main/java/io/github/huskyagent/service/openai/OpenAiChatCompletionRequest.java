package io.github.huskyagent.service.openai;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record OpenAiChatCompletionRequest(
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<Message> messages,
        @JsonProperty("stream") Boolean stream,
        @JsonProperty("user") String user,
        @JsonProperty("metadata") Map<String, Object> metadata,
        @JsonProperty("temperature") Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
        @JsonProperty("stop") Object stop,
        @JsonProperty("tools") Object tools,
        @JsonProperty("tool_choice") Object toolChoice,
        @JsonProperty("response_format") Object responseFormat,
        @JsonProperty("extra_body") Map<String, Object> extraBody) {

    public OpenAiChatCompletionRequest {
        if (extraBody == null) {
            extraBody = new HashMap<>();
        }
    }

    public boolean streamEnabled() {
        return Boolean.TRUE.equals(stream);
    }

    @JsonAnyGetter
    public Map<String, Object> extraBody() {
        return extraBody;
    }

    @JsonAnySetter
    private void setExtraBodyProperty(String key, Object value) {
        if (extraBody != null) {
            extraBody.put(key, value);
        }
    }

    public record Message(
            @JsonProperty("role") String role,
            @JsonProperty("content") Object content,
            @JsonProperty("name") String name,
            @JsonProperty("tool_call_id") String toolCallId,
            @JsonProperty("tool_calls") Object toolCalls) {
    }
}
