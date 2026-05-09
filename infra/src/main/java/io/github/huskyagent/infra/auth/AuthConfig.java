package io.github.huskyagent.infra.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthConfig {

    private boolean enabled = true;

    private List<String> apiKeys = new ArrayList<>();
}