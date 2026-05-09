package io.github.huskyagent.application.channel.binding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "channel-bindings")
public class ChannelBindingProperties {
    private String defaultScene;
    private Set<String> allowExplicitSceneOverrideFor = Set.of("http");
    private Map<String, BindingProperties> bindings = new LinkedHashMap<>();

    @Data
    public static class BindingProperties {
        private String channelType;
        private String platformAccountId;
        private String sceneId;
        private boolean enabled = true;
        private String displayName;
        private Map<String, String> metadata = Map.of();
    }
}
