package io.github.huskyagent.infra.skill;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "skillhub")
public class SkillHubConfig {

    private boolean enabled = true;

    private String apiUrl = "https://www.skillhub.club/api/v1";

    private String apiKey = "";

    private int requestTimeoutSeconds = 30;
}