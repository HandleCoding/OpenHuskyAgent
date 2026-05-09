package io.github.huskyagent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 核心配置 — 模型、LLM 调用、辅助模型、工具、图参数统一收口
 */
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
        /** 独立 base-url，空则复用主模型 */
        private String baseUrl;
        /** 独立 api-key，空则复用主模型 key */
        private String apiKey;
        /** 独立 completions-path，空则使用 OpenAI 默认值 */
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

    /**
     * 工具使用强制指导配置
     *
     * <p>控制 ToolUseEnforcementSection 的行为，防止 LLM 只说不做。</p>
     *
     * <ul>
     *   <li>"auto"（默认）— 模型名匹配 TOOL_USE_ENFORCEMENT_MODELS 时注入</li>
     *   <li>true / "always" / "yes" / "on" — 所有模型都注入</li>
     *   <li>false / "never" / "no" / "off" — 永不注入</li>
     *   <li>List — 自定义模型名子串列表，匹配则注入</li>
     * </ul>
     */
    @Data
    public static class ToolUseEnforcementConfig {
        private Object enforcement = "auto";
    }

    @Data
    public static class GraphConfig {
        private int maxReactLoops = 30;
    }
}