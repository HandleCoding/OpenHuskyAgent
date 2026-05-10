package io.github.huskyagent.service.channel.slack;

import com.slack.api.bolt.socket_mode.SocketModeApp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SlackSocketModeRunner {

    private final SlackInstanceRegistry registry;
    private final Map<String, SocketModeApp> apps = new ConcurrentHashMap<>();

    public SlackSocketModeRunner(SlackInstanceRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void start() {
        Set<String> appTokens = new HashSet<>();
        var enabledInstances = registry.all().values().stream()
                .filter(instance -> instance.properties().isEnabled())
                .toList();
        enabledInstances.forEach(instance -> {
            SlackProperties.InstanceProperties properties = instance.properties();
            if (isBlank(properties.getAppToken())) {
                throw new IllegalStateException("Slack app token is required: instanceId=" + instance.instanceId());
            }
            if (isBlank(properties.getBotToken())) {
                throw new IllegalStateException("Slack bot token is required: instanceId=" + instance.instanceId());
            }
            if (isBlank(properties.getBotUserId())) {
                throw new IllegalStateException("Slack bot user id is required for channel binding: instanceId=" + instance.instanceId());
            }
            if (!appTokens.add(properties.getAppToken())) {
                throw new IllegalStateException("Duplicate Slack app token configured for enabled instance: instanceId=" + instance.instanceId());
            }
        });
        enabledInstances.forEach(this::startApp);
    }

    @PreDestroy
    public void stop() {
        apps.values().forEach(app -> {
            try {
                app.stop();
            } catch (Exception e) {
                log.warn("Slack Socket Mode stop failed", e);
            }
        });
        apps.clear();
    }

    private void startApp(SlackInstance instance) {
        try {
            SocketModeApp socketModeApp = new SocketModeApp(instance.properties().getAppToken(), instance.app());
            socketModeApp.startAsync();
            apps.put(instance.instanceId(), socketModeApp);
            log.info("Slack Socket Mode started: instanceId={}, botUserId={}",
                    instance.instanceId(), instance.adapter().platformAccountId());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start Slack Socket Mode: instanceId=" + instance.instanceId(), e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
