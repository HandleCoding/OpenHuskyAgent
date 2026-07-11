package io.github.huskyagent.infra.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Platform-level LLM provider directory. Agents reference providers by id via model selection.
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /**
     * Default provider id when an agent omits {@code model.provider}.
     */
    private String defaultProvider = "main";

    private Map<String, Provider> providers = new LinkedHashMap<>();

    @Data
    public static class Provider {
        /**
         * Wire protocol: {@code openai_chat_completions} or {@code anthropic_messages}.
         * Prefer this over legacy {@link #type}.
         */
        private String protocol;
        /**
         * Legacy alias for protocol. Values like {@code openai-compatible} still work.
         */
        private String type = "openai-compatible";
        private String baseUrl;
        private String apiKey;
        private String completionsPath = "/v1/chat/completions";
        /**
         * Anthropic Messages path (relative to base-url). Default {@code /v1/messages}.
         */
        private String messagesPath = "/v1/messages";
        /**
         * Anthropic API version header.
         */
        private String anthropicVersion = "2023-06-01";
        /**
         * Optional default model name when agent selection omits model name.
         */
        private String model;
        private Double temperature;

        /**
         * Effective protocol config string (protocol preferred, else type).
         */
        public String effectiveProtocolConfig() {
            if (protocol != null && !protocol.isBlank()) {
                return protocol.trim();
            }
            return type;
        }
    }
}
