package io.github.huskyagent.infra.chatbot;

import io.github.huskyagent.infra.tool.Toolset;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "chatbot")
public class ChatbotConfig {

    private boolean enabled = true;

    /** @deprecated Configure chatbot runtime behavior under scenes.configs.chatbot. */
    @Deprecated(forRemoval = true)
    private Set<Toolset> allowedToolsets;

    /** @deprecated Configure chatbot runtime behavior under scenes.configs.chatbot. */
    @Deprecated(forRemoval = true)
    private Set<String> deniedTools;

    /** @deprecated Configure chatbot runtime behavior under scenes.configs.chatbot. */
    @Deprecated(forRemoval = true)
    private String systemPrompt;

    @PostConstruct
    void warnDeprecatedRuntimeFields() {
        if (allowedToolsets != null || deniedTools != null || systemPrompt != null) {
            log.warn("chatbot.allowed-toolsets, chatbot.denied-tools, and chatbot.system-prompt are deprecated and ignored; configure chatbot runtime behavior under scenes.configs.chatbot");
        }
    }
}
