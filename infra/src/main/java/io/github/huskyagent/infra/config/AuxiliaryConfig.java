package io.github.huskyagent.infra.config;

import io.github.huskyagent.infra.ai.AuxiliaryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuxiliaryConfig {

    @Bean
    public AuxiliaryClient auxiliaryClient(
            AgentConfig agentConfig,
            @Value("${spring.ai.openai.base-url:}") String sharedBaseUrl,
            @Value("${spring.ai.openai.api-key:}") String sharedApiKey,
            @Value("${spring.ai.openai.completions-path:/v1/chat/completions}") String sharedCompletionsPath) {
        return AuxiliaryClient.create(
                agentConfig.getAuxiliary(),
                sharedBaseUrl,
                sharedApiKey,
                sharedCompletionsPath);
    }
}
