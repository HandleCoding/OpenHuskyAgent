package io.github.huskyagent.application.subagent;

import io.github.huskyagent.infra.llm.ModelSelection;
import io.github.huskyagent.infra.tool.Toolset;

import java.nio.file.Path;
import java.util.Set;

public record SubAgentTask(
        String goal,
        String context,
        Set<Toolset> allowedToolsets,
        int maxSteps,
        long timeoutSeconds,
        Path workingDirectory,
        int taskIndex,
        /**
         * Effective child model. Null means platform default after {@link io.github.huskyagent.application.runtime.RuntimePolicyResolver} assemble.
         */
        ModelSelection modelSelection
) {
    public SubAgentTask(String goal, String context, Set<Toolset> allowedToolsets,
                        int maxSteps, long timeoutSeconds, Path workingDirectory, int taskIndex) {
        this(goal, context, allowedToolsets, maxSteps, timeoutSeconds, workingDirectory, taskIndex, null);
    }
}
