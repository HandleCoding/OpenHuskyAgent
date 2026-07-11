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
         * Protocol type. v1 only supports {@code openai-compatible}.
         */
        private String type = "openai-compatible";
        private String baseUrl;
        private String apiKey;
        private String completionsPath = "/v1/chat/completions";
        /**
         * Optional default model name when agent selection omits model name.
         */
        private String model;
        private Double temperature;
    }
}
