package io.github.huskyagent.infra.config;

import io.github.huskyagent.infra.ai.AuxiliaryClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuxiliaryConfig {

    @Bean
    public AuxiliaryClient auxiliaryClient(ChatModel chatModel, AgentConfig agentConfig) {
        return new AuxiliaryClient(chatModel, agentConfig.getAuxiliary());
    }
}