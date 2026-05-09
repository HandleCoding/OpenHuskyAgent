package io.github.huskyagent.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tui.ws")
public class TuiWsConfig {

    private String path = "/api/tui";
    private String allowedOrigins = "*";
}