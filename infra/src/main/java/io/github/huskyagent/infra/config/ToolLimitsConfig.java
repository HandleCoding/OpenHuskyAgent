package io.github.huskyagent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tool.limits")
public class ToolLimitsConfig {

    // ── TerminalTool ──
    private int terminalMaxOutputChars = 200_000;
    private int terminalDefaultTimeout = 120;
    private int terminalMaxTimeout = 600;
    private int terminalDefaultWaitTimeout = 300;

    // ── ReadFileTool ──
    private int readFileMaxChars = 100_000;
    private int readFileMaxLines = 2000;

    // ── ToolResultPruner ──
    private int pruneMaxToolResultLength = 2000;
    private int pruneMaxArgumentsLength = 1000;
}