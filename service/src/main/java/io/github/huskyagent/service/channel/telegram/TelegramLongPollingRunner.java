package io.github.huskyagent.service.channel.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetUpdates;
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
public class TelegramLongPollingRunner {

    private final TelegramInstanceRegistry registry;
    private final Map<String, TelegramBot> bots = new ConcurrentHashMap<>();

    public TelegramLongPollingRunner(TelegramInstanceRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void start() {
        Set<String> tokens = new HashSet<>();
        registry.all().values().stream()
                .filter(instance -> instance.properties().isEnabled())
                .forEach(instance -> {
                    String token = instance.properties().getToken();
                    if (token != null && !token.isBlank() && !tokens.add(token)) {
                        throw new IllegalStateException("Duplicate Telegram bot token configured for enabled instance: instanceId=" + instance.instanceId());
                    }
                    startBot(instance);
                });
    }

    @PreDestroy
    public void stop() {
        bots.values().forEach(TelegramBot::removeGetUpdatesListener);
        bots.clear();
    }

    private void startBot(TelegramInstance instance) {
        TelegramProperties.InstanceProperties properties = instance.properties();
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            throw new IllegalStateException("Telegram bot token is required: instanceId=" + instance.instanceId());
        }
        TelegramBot bot = instance.apiClient().bot();
        if (bot == null) {
            throw new IllegalStateException("Telegram bot client is missing: instanceId=" + instance.instanceId());
        }
        String username = instance.adapter().platformAccountId();
        GetUpdates getUpdates = new GetUpdates().timeout(Math.max(1, properties.getLongPollingTimeoutSeconds()));
        bot.setUpdatesListener(updates -> {
            for (Update update : updates) {
                try {
                    instance.eventHandler().handleUpdate(update);
                } catch (Exception e) {
                    log.error("Telegram update handling failed: instanceId={}, updateId={}",
                            instance.instanceId(), update != null ? update.updateId() : null, e);
                }
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        }, e -> log.error("Telegram long polling failed: instanceId={}", instance.instanceId(), e), getUpdates);
        bots.put(instance.instanceId(), bot);
        log.info("Telegram long polling started: instanceId={}, botUsername={}, timeoutSeconds={}",
                instance.instanceId(), username, getUpdates.getTimeoutSeconds());
    }
}
