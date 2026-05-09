package io.github.huskyagent.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {

    private boolean enabled = true;

    private String url;

    private String noProxy;

    private boolean envEnabled = true;

    private boolean detectSystem = false;
}
