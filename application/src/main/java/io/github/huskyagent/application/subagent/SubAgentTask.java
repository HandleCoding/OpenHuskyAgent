package io.github.huskyagent.application.subagent;

import io.github.huskyagent.infra.tool.Toolset;

import java.nio.file.Path;
import java.util.Set;

/**
 * 子 Agent 任务描述
 */
public record SubAgentTask(
        String goal,
        String context,
        Set<Toolset> allowedToolsets,
        int maxSteps,
        long timeoutSeconds,
        Path workingDirectory,
        int taskIndex
) {}
