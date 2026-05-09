package io.github.huskyagent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "agent.delegation")
public class SubAgentConfig {

    private boolean enabled = true;

    private int maxIterations = 50;

    private int maxConcurrentChildren = 3;

    private int maxSpawnDepth = 1;

    private long childTimeoutSeconds = 600;

    private List<String> blockedToolsets = List.of("DELEGATE", "MEMORY");

    private String model = "";
}
