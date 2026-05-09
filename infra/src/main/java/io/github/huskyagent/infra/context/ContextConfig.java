package io.github.huskyagent.infra.context;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Context 压缩配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "context")
public class ContextConfig {

    /**
     * 触发压缩的阈值百分比（相对于 contextLength）
     */
    private double thresholdPercent = 0.75;

    /**
     * 保护前 N 条消息（系统提示 + 第一轮对话）
     */
    private int protectFirstN = 3;

    /**
     * 保护尾部消息的 token 预算
     */
    private int tailTokenBudget = 20000;

    /**
     * 摘要最大 token 数
     */
    private int maxSummaryTokens = 4000;

    /**
     * 模型的 context length
     */
    private int contextLength = 128000;

    /**
     * 按主模型覆盖 context length，key 使用模型名小写形式。
     */
    private Map<String, Integer> modelContextLengths = new HashMap<>();

    /**
     * 辅助模型配置（用于摘要生成）
     */
    private String summaryModel = "glm-5";
    private String summaryModelBaseUrl;
    private String summaryModelApiKey;

    /**
     * 计算触发压缩的阈值 token 数
     */
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