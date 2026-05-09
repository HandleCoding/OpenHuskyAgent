package io.github.huskyagent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentConfig {

    private LlmConfig llm = new LlmConfig();
    private AuxiliaryConfig auxiliary = new AuxiliaryConfig();
    private ToolConfig tool = new ToolConfig();
    private GraphConfig graph = new GraphConfig();
    private ToolUseEnforcementConfig toolUseEnforcement = new ToolUseEnforcementConfig();

    @Data
    public static class LlmConfig {
        private int maxRetries = 3;
        private long initialBackoffMs = 500;
        private int connectTimeoutSeconds = 30;
        private long readTimeoutMinutes = 5;
        private long callBlockTimeoutMinutes = 5;
    }

    @Data
    public static class AuxiliaryConfig {
        private String model = "glm-5";
        private String baseUrl;
        private String apiKey;
        private String completionsPath;
        private double temperature = 0.3;
        private int maxTokens = 1000;
        private int webSummaryMaxTokens = 2000;
    }

    @Data
    public static class ToolConfig {
        private int maxRetries = 3;
        private int executionTimeoutSeconds = 120;
    }

    @Data
    public static class ToolUseEnforcementConfig {
        private Object enforcement = "auto";
    }

    @Data
    public static class GraphConfig {
        private int maxReactLoops = 30;
    }
}