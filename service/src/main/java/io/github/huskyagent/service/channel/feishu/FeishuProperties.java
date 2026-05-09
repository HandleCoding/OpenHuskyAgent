package io.github.huskyagent.service.channel.feishu;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "channels.feishu")
public class FeishuProperties {
    private Map<String, InstanceProperties> instances = new LinkedHashMap<>();

    @Data
    public static class InstanceProperties {
        private boolean enabled = false;
        private String transport = "webhook";
        private String appId = "";
        private String appSecret = "";
        private String verificationToken = "";
        private String encryptKey = "";
        private String botOpenId = "";
        private String defaultScene = "feishu-qa";
        private boolean mentionRequiredInGroup = true;
        private GroupSessionScope groupSessionScope = GroupSessionScope.THREAD;
        private boolean showToolCalls = true;
        private int approvalTimeoutSeconds = 300;
        private int maxInboundImageBytes = 10 * 1024 * 1024;
    }

    public enum GroupSessionScope {
        USER,
        THREAD,
        CHAT
    }
}
