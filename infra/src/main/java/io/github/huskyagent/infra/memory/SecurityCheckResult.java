package io.github.huskyagent.infra.memory;

import java.util.List;

public record SecurityCheckResult(
    List<String> warnings,
    boolean blocked,
    String blockReason
) {

    public static SecurityCheckResult ok() {
        return new SecurityCheckResult(List.of(), false, null);
    }

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    public String warningsText() {
        return warnings != null ? String.join("; ", warnings) : "";
    }
}