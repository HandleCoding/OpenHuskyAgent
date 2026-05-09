package io.github.huskyagent.infra.context;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "context")
public class ContextConfig {

    private double thresholdPercent = 0.75;

    private int protectFirstN = 3;

    private int tailTokenBudget = 20000;

    private int maxSummaryTokens = 4000;

    private int contextLength = 128000;

    private Map<String, Integer> modelContextLengths = new HashMap<>();

    private String summaryModel = "glm-5";
    private String summaryModelBaseUrl;
    private String summaryModelApiKey;

    public int getThresholdTokens() {
        return (int) (contextLength * thresholdPercent);
    }

    public int resolveContextLength(String modelName) {
        String normalized = normalizeModelName(modelName);
        if (normalized == null || modelContextLengths == null || modelContextLengths.isEmpty()) {
            return contextLength;
        }
        Integer exact = modelContextLengths.get(normalized);
        if (exact != null && exact > 0) {
            return exact;
        }
        return modelContextLengths.entrySet().stream()
                .filter(entry -> normalized.equals(normalizeModelName(entry.getKey())))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && value > 0)
                .findFirst()
                .orElse(contextLength);
    }

    private String normalizeModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        return modelName.trim().toLowerCase(Locale.ROOT);
    }
}