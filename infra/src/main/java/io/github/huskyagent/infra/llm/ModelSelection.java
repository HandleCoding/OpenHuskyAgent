package io.github.huskyagent.infra.llm;

import lombok.Builder;
import lombok.Value;

/**
 * Effective LLM selection for an agent run: which named provider and which model.
 * Credentials and endpoints live on the platform provider registry, not here.
 */
@Value
@Builder
public class ModelSelection {
    /**
     * Named entry under {@code llm.providers.*}. Null/blank means the configured default provider.
     */
    String providerId;
    /**
     * Upstream model id (e.g. gpt-5.4, deepseek-chat). Null/blank means the provider or global default model.
     */
    String modelName;
    Double temperature;
    Integer maxTokens;

    public String fingerprint() {
        return String.join("|",
                nullToEmpty(providerId),
                nullToEmpty(modelName),
                temperature != null ? temperature.toString() : "",
                maxTokens != null ? maxTokens.toString() : "");
    }

    public String effectiveProviderId(String defaultProviderId) {
        if (providerId != null && !providerId.isBlank()) {
            return providerId.trim();
        }
        return defaultProviderId != null ? defaultProviderId.trim() : "main";
    }

    private static String nullToEmpty(String value) {
        return value != null ? value.trim() : "";
    }
}
