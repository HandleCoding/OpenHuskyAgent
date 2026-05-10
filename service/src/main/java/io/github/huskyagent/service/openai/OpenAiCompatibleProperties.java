package io.github.huskyagent.service.openai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "openai-compatible")
public class OpenAiCompatibleProperties {

    private boolean enabled = false;

    private String platformAccountId = "openai-compatible";

    private String defaultUserId = "openai-client";

    private long streamTimeoutMs = 300_000L;

    private String modelPrefix;
}
