package io.github.huskyagent.service.channel.telegram;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "channels.telegram")
public class TelegramProperties {
    private Map<String, InstanceProperties> instances = new LinkedHashMap<>();

    @Data
    public static class InstanceProperties {
        private boolean enabled = false;
        private String token = "";
        private String botUsername = "";
        private String defaultScene = "assistant";
        private boolean mentionRequiredInGroup = true;
        private GroupSessionScope groupSessionScope = GroupSessionScope.THREAD;
        private boolean showToolCalls = true;
        private int approvalTimeoutSeconds = 300;
        private int longPollingTimeoutSeconds = 50;
    }

    public enum GroupSessionScope {
        USER,
        THREAD,
        CHAT
    }
}
