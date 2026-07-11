package io.github.huskyagent.service.channel.telegram;

import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.application.channel.ChannelRuntimeService;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.Principal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelegramInstanceEventHandlerTest {

    @Test
    void dispatchesNormalMessageToRuntime() {
        TelegramInstanceAdapter adapter = mock(TelegramInstanceAdapter.class);
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        TelegramInstanceEventHandler handler = handler(adapter, runtimeService, new TelegramInboundDeduplicator());
        Update update = update(100, new Message());
        when(adapter.normalizeInbound(any(), any())).thenReturn(inbound("100:11"));
        when(adapter.platformAccountId()).thenReturn("test_bot");
        when(runtimeService.handleInboundAsync(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ChatResult.success("ok", "session-1", false)));

        handler.handleUpdate(update);

        verify(runtimeService).handleInboundAsync(any(), eq(adapter), any());
    }

    @Test
    void duplicateMessageIsDropped() {
        TelegramInstanceAdapter adapter = mock(TelegramInstanceAdapter.class);
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        TelegramInstanceEventHandler handler = handler(adapter, runtimeService, new TelegramInboundDeduplicator());
        when(adapter.normalizeInbound(any(), any())).thenReturn(inbound("100:11"));
        when(adapter.platformAccountId()).thenReturn("test_bot");
        when(runtimeService.handleInboundAsync(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ChatResult.success("ok", "session-1", false)));

        handler.handleUpdate(update(100, new Message()));
        handler.handleUpdate(update(100, new Message()));

        verify(runtimeService, times(1)).handleInboundAsync(any(), any(), any());
    }

    @Test
    void approvalCallbackCompletesThroughAdapter() {
        TelegramInstanceAdapter adapter = mock(TelegramInstanceAdapter.class);
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        TelegramInstanceEventHandler handler = handler(adapter, runtimeService, new TelegramInboundDeduplicator());
        Update update = callbackUpdate("cb-1", 1001L, "husky:a:abc123:approve");
        when(adapter.handleCallback(any())).thenReturn(true);

        handler.handleUpdate(update);

        verify(adapter).handleCallback(update.callbackQuery());
        verify(runtimeService, never()).handleInboundAsync(any(), any(), any());
    }

    @Test
    void staleCallbackDoesNotDispatchToRuntime() {
        TelegramInstanceAdapter adapter = mock(TelegramInstanceAdapter.class);
        ChannelRuntimeService runtimeService = mock(ChannelRuntimeService.class);
        TelegramInstanceEventHandler handler = handler(adapter, runtimeService, new TelegramInboundDeduplicator());
        Update update = callbackUpdate("cb-1", 1001L, "husky:a:missing:approve");
        when(adapter.handleCallback(any())).thenReturn(false);

        handler.handleUpdate(update);

        verify(adapter).handleCallback(update.callbackQuery());
        verify(runtimeService, never()).handleInboundAsync(any(), any(), any());
    }

    private TelegramInstanceEventHandler handler(TelegramInstanceAdapter adapter,
                                                 ChannelRuntimeService runtimeService,
                                                 TelegramInboundDeduplicator deduplicator) {
        return new TelegramInstanceEventHandler(adapter, runtimeService, Runnable::run, deduplicator);
    }

    private InboundMessage inbound(String messageId) {
        return InboundMessage.builder()
                .messageId(messageId)
                .text("hello")
                .principal(Principal.builder().id("telegram:test_bot:1001").channelType(ChannelType.TELEGRAM).build())
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.TELEGRAM)
                        .platformAccountId("test_bot")
                        .chatId("1001")
                        .senderId("1001")
                        .build())
                .agentId("assistant")
                .build();
    }

    private Update update(int updateId, Message message) {
        Update update = new Update();
        set(update, "update_id", updateId);
        set(update, "message", message);
        return update;
    }

    private Update callbackUpdate(String callbackId, long senderId, String data) {
        CallbackQuery callbackQuery = new CallbackQuery();
        set(callbackQuery, "id", callbackId);
        set(callbackQuery, "from", new User(senderId));
        set(callbackQuery, "message", new Message());
        set(callbackQuery, "data", data);
        Update update = new Update();
        set(update, "update_id", 100);
        set(update, "callback_query", callbackQuery);
        return update;
    }

    private void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
