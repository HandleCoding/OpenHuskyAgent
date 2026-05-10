package io.github.huskyagent.service.channel.telegram;

import com.pengrad.telegrambot.ExceptionHandler;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.request.GetUpdates;
import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.application.channel.runtime.ToolDisplayMessageRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

class TelegramLongPollingRunnerTest {

    @Test
    void appliesConfiguredLongPollingTimeout() {
        CapturingTelegramBot bot = new CapturingTelegramBot();
        TelegramInstance instance = instance("assistant-bot", "token-1", 37, bot);
        TelegramLongPollingRunner runner = new TelegramLongPollingRunner(registry(instance));

        runner.start();

        assertNotNull(bot.getUpdates);
        assertEquals(37, bot.getUpdates.getTimeoutSeconds());
    }

    @Test
    void rejectsDuplicateEnabledTokensInSameProcess() {
        TelegramLongPollingRunner runner = new TelegramLongPollingRunner(registry(
                instance("assistant-bot", "token-1", 30, new CapturingTelegramBot()),
                instance("qa-bot", "token-1", 30, new CapturingTelegramBot())
        ));

        IllegalStateException error = assertThrows(IllegalStateException.class, runner::start);
        assertTrue(error.getMessage().contains("Duplicate Telegram bot token"));
    }

    private TelegramInstance instance(String instanceId, String token, int timeoutSeconds, TelegramBot bot) {
        TelegramProperties.InstanceProperties properties = new TelegramProperties.InstanceProperties();
        properties.setEnabled(true);
        properties.setToken(token);
        properties.setBotUsername(instanceId.replace('-', '_'));
        properties.setLongPollingTimeoutSeconds(timeoutSeconds);
        TelegramApiClient apiClient = new TelegramApiClient(properties, bot);
        TelegramInstanceAdapter adapter = new TelegramInstanceAdapter(properties, apiClient, new ToolDisplayMessageRenderer());
        return new TelegramInstance(
                instanceId,
                properties,
                apiClient,
                adapter,
                new TelegramInstanceEventHandler(adapter, mock(ChannelRuntimeService.class), Runnable::run, new TelegramInboundDeduplicator())
        );
    }

    private TelegramInstanceRegistry registry(TelegramInstance... instances) {
        TelegramInstanceRegistry registry = mock(TelegramInstanceRegistry.class);
        Map<String, TelegramInstance> values = new java.util.LinkedHashMap<>();
        for (TelegramInstance instance : instances) {
            values.put(instance.instanceId(), instance);
        }
        org.mockito.Mockito.when(registry.all()).thenReturn(values);
        return registry;
    }

    private static class CapturingTelegramBot extends TelegramBot {
        GetUpdates getUpdates;

        CapturingTelegramBot() {
            super("test-token");
        }

        @Override
        public void setUpdatesListener(UpdatesListener listener, ExceptionHandler exceptionHandler, GetUpdates getUpdates) {
            this.getUpdates = getUpdates;
        }

        @Override
        public void removeGetUpdatesListener() {
        }
    }
}
