package io.github.huskyagent.service.channel.feishu;

import com.lark.oapi.ws.Client;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class FeishuWebSocketClientRunner {

    private final FeishuInstanceRegistry registry;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    public FeishuWebSocketClientRunner(FeishuInstanceRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void start() {
        registry.all().values().stream()
                .filter(instance -> instance.properties().isEnabled())
                .filter(instance -> "websocket".equalsIgnoreCase(instance.properties().getTransport()))
                .forEach(this::startClient);
    }

    private void startClient(FeishuInstance instance) {
        FeishuProperties.InstanceProperties properties = instance.properties();
        if (properties.getAppId() == null || properties.getAppId().isBlank()
                || properties.getAppSecret() == null || properties.getAppSecret().isBlank()) {
            throw new IllegalStateException("Feishu app-id/app-secret are required for websocket transport: instanceId="
                    + instance.instanceId());
        }
        Client client = new Client.Builder(properties.getAppId(), properties.getAppSecret())
                .eventHandler(instance.eventHandler().larkEventDispatcher())
                .autoReconnect(true)
                .build();
        client.start();
        clients.put(instance.instanceId(), client);
        log.info("Feishu websocket client started: instanceId={}", instance.instanceId());
        instance.apiClient().initBotOpenId();
    }
}