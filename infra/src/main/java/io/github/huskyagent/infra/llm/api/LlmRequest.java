package io.github.huskyagent.infra.llm.api;

import java.util.List;
import java.util.Map;

/**
 * Protocol-neutral model request.
 */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        List<LlmToolDefinition> tools,
        Double temperature,
        Integer maxTokens,
        boolean stream,
        Map<String, Object> extra
) {
    public LlmRequest {
        if (messages == null) {
            messages = List.of();
        }
        if (tools == null) {
            tools = List.of();
        }
        if (extra == null) {
            extra = Map.of();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String model;
        private List<LlmMessage> messages = List.of();
        private List<LlmToolDefinition> tools = List.of();
        private Double temperature;
        private Integer maxTokens;
        private boolean stream = true;
        private Map<String, Object> extra = Map.of();

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<LlmMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder tools(List<LlmToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder extra(Map<String, Object> extra) {
            this.extra = extra;
            return this;
        }

        public LlmRequest build() {
            return new LlmRequest(model, messages, tools, temperature, maxTokens, stream, extra);
        }
    }
}
