package io.github.huskyagent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "agent.delegation")
public class SubAgentConfig {

    /** 是否启用子 Agent 委派 */
    private boolean enabled = true;

    /** 子 Agent 最大 ReAct 循环次数 */
    private int maxIterations = 50;

    /** 最大并行子 Agent 数（v1 单任务，预留批量能力） */
    private int maxConcurrentChildren = 3;

    /** 子 Agent 嵌套深度上限（1=扁平，不可递归委派） */
    private int maxSpawnDepth = 1;

    /** 子 Agent 执行超时（秒） */
    private long childTimeoutSeconds = 600;

    /** 子 Agent 禁用的工具分组 */
    private List<String> blockedToolsets = List.of("DELEGATE", "MEMORY");

    /** 子 Agent 使用的模型（空=同父 Agent） */
    private String model = "";
}
