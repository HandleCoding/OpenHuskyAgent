package io.github.huskyagent.service.channel.telegram;

import com.pengrad.telegrambot.model.Update;
import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.InboundMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;

@Slf4j
public class TelegramInstanceEventHandler {

    private final TelegramInstanceAdapter adapter;
    private final ChannelRuntimeService runtimeService;
    private final Executor agentExecutor;
    private final TelegramInboundDeduplicator deduplicator;

    public TelegramInstanceEventHandler(TelegramInstanceAdapter adapter,
                                        ChannelRuntimeService runtimeService,
                                        Executor agentExecutor,
                                        TelegramInboundDeduplicator deduplicator) {
        this.adapter = adapter;
        this.runtimeService = runtimeService;
        this.agentExecutor = agentExecutor;
        this.deduplicator = deduplicator;
    }

    public void handleUpdate(Update update) {
        if (update == null) {
            return;
        }
        if (update.callbackQuery() != null) {
            boolean handled = adapter.handleCallback(update.callbackQuery());
            if (!handled) {
                log.info("Ignored Telegram callback query: updateId={}", update.updateId());
            }
            return;
        }
        ChannelAuthContext authContext = ChannelAuthContext.builder().build();
        InboundMessage inbound = adapter.normalizeInbound(update, authContext);
        if (inbound.isIgnored()) {
            log.info("Ignored Telegram update: updateId={}", update.updateId());
            return;
        }
        if (deduplicator.isDuplicate(adapter.platformAccountId(), inbound.getMessageId())) {
            log.info("Dropped duplicate Telegram update: platformAccountId={}, messageId={}",
                    adapter.platformAccountId(), inbound.getMessageId());
            return;
        }
        log.info("Dispatching Telegram inbound message: principal={}, chatId={}, textLength={}",
                inbound.getPrincipal() != null ? inbound.getPrincipal().getId() : null,
                inbound.getChannelIdentity() != null ? inbound.getChannelIdentity().getChatId() : null,
                inbound.getText() != null ? inbound.getText().length() : 0);
        runtimeService.handleInboundAsync(inbound, adapter, agentExecutor)
                .exceptionally(error -> {
                    log.error("Telegram inbound handling failed", error);
                    return null;
                });
    }
}
