package io.github.huskyagent.service.channel.slack;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "channels.slack")
public class SlackProperties {
    private Map<String, InstanceProperties> instances = new LinkedHashMap<>();

    @Data
    public static class InstanceProperties {
        private boolean enabled = false;
        private String botToken = "";
        private String appToken = "";
        private String botUserId = "";
        private String botName = "";
        private String teamId = "";
        private String defaultScene = "assistant";
        private boolean mentionRequiredInChannel = true;
        private GroupSessionScope groupSessionScope = GroupSessionScope.THREAD;
        private boolean showToolCalls = true;
        private int approvalTimeoutSeconds = 300;
        private boolean sendTypingStatus = true;
        private boolean replyBroadcast = false;
    }

    public enum GroupSessionScope {
        USER,
        THREAD,
        CHANNEL
    }
}
