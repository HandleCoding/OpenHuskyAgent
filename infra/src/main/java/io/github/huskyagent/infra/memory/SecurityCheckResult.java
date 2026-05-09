package io.github.huskyagent.infra.memory;

import java.util.List;

/**
 * 安全检查结果
 */
public record SecurityCheckResult(
    List<String> warnings,
    boolean blocked,
    String blockReason
) {

    /**
     * 创建通过的结果
     */
    public static SecurityCheckResult ok() {
        return new SecurityCheckResult(List.of(), false, null);
    }

    /**
     * 是否有警告
     */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    /**
     * 获取警告信息字符串
     */
    public String warningsText() {
        return warnings != null ? String.join("; ", warnings) : "";
    }
}