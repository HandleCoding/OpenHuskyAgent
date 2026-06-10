package io.github.huskyagent.service.channel.telegram;

import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import io.github.huskyagent.application.channel.runtime.ToolDisplayMessageRenderer;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ClarifyDecision;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.OutboundMessage;
import io.github.huskyagent.infra.channel.ReplyTarget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TelegramInstanceAdapterTest {

    @Test
    void normalizesPrivateTextMessage() {
        RecordingTelegramApiClient apiClient = new RecordingTelegramApiClient(properties());
        TelegramInstanceAdapter adapter = adapter(apiClient, false);

        InboundMessage inbound = adapter.normalizeInbound(
                update(100, message(11, 1001L, "Alice", Chat.Type.Private, 1001L, null, "hello", null)),
                ChannelAuthContext.builder().connectionId("c1").build());

        assertFalse(inbound.isIgnored());
        assertEquals("hello", inbound.getText());
        assertEquals("telegram:test_bot:1001", inbound.getPrincipal().getId());
        assertEquals("Alice", inbound.getPrincipal().getDisplayName());
        assertEquals(ConversationType.DIRECT, inbound.getChannelIdentity().getConversationType());
        assertEquals("test_bot", inbound.getChannelIdentity().getPlatformAccountId());
        assertEquals("1001", inbound.getChannelIdentity().getChatId());
        assertEquals("11", inbound.getReplyTarget().getMessageId());
        assertNull(inbound.getSceneId());
    }

    @Test
    void ignoresUnmentionedGroupMessageWhenMentionRequired() {
        RecordingTelegramApiClient apiClient = new RecordingTelegramApiClient(properties());
        TelegramInstanceAdapter adapter = adapter(apiClient, true);

        InboundMessage inbound = adapter.normalizeInbound(
                update(100, message(11, 1001L, "Alice", Chat.Type.group, -200L, null, "hello", null)),
                ChannelAuthContext.builder().build());

        assertTrue(inbound.isIgnored());
    }

    @Test
    void acceptsMentionedGroupMessageAndStripsMention() {
        RecordingTelegramApiClient apiClient = new RecordingTelegramApiClient(properties());
        TelegramInstanceAdapter adapter = adapter(apiClient, true);

        InboundMessage inbound = adapter.normalizeInbound(
                update(100, message(11, 1001L, "Alice", Chat.Type.group, -200L, null, "@test_bot help me", null)),
                ChannelAuthContext.builder().build());

        assertFalse(inbound.isIgnored());
        assertEquals("help me", inbound.getText());
        assertEquals(ConversationType.GROUP, inbound.getChannelIdentity().getConversationType());
        assertEquals("telegram:test_bot:chat:-200:thread:main", inbound.getPrincipal().getId());
    }

    @Test
    void ignoresSimilarButNotExactGroupMention() {
        RecordingTelegramApiClient apiClient = new RecordingTelegramApiClient(properties());
        TelegramInstanceAdapter adapter = adapter(apiClient, true);

        InboundMessage inbound = adapter.normalizeInbound(
                update(100, message(11, 1001L, "Alice", Chat.Type.group, -200L, null, "@test_botched help", null)),
                ChannelAuthContext.builder().build());

        assertTrue(inbound.isIgnored());
    }

    @Test
    void stripsBotSuffixFromCommandsButKeepsCommand() {
        RecordingTelegramApiClient apiClient = new RecordingTelegramApiClient(properties());
        TelegramInstanceAdapter adapter = adapter(apiClient, true);

        InboundMessage inbound = adapter.normalizeInbound(
                update(100, message(11, 1001L, "Alice", Chat.Type.group, -200L, null, "/ask@test_bot help", null)),
                ChannelAuthContext.builder().build());

        assertFalse(inbound.isIgnored());
        assertEquals("/ask help", inbound.getText());
    }

    @Test
    void unmentionedGroupMessageDoesNotCompleteOpenClarifyWhenMentionRequired() {
        RecordingTelegramApiClient apiClient = new RecordingTelegramApiClient(properties());
        TelegramInstanceAdapter adapter = adapter(apiClient, true);
        assertFalse(adapter.completePendingOpenClarify("-200", null, "1001", "ordinary chat"));

        InboundMessage inbound = adapter.normalizeInbound(
                update(101, message(12, 1001L, "Alice", Chat.Type.group, -200L, null, "ordinary chat", null)),
                ChannelAuthContext.builder().build());

        assertTrue(inbound.isIgnored());
        assertNull(apiClient.lastClarifyStatus);
    }

    @Test
    void mapsForumTopicToThreadConversation() {
        RecordingTelegramApiClient apiClient = new RecordingTelegramApiClient(properties());
        TelegramInstanceAdapter adapter = adapter(apiClient, true);

        InboundMessage inbound = adapter.normalizeInbound(
                update(100, message(11, 1001L, "Alice", Chat.Type.supergroup, -200L, 333L, "@test_bot help", null)),
                ChannelAuthContext.builder().build());

        assertFalse(inbound.isIgnored());
        assertEquals(ConversationType.THREAD, inbound.getChannelIdentity().getConversationType());
        assertEquals("333", inbound.getChannelIdentity().getThreadId());
        assertEquals("333", inbound.getReplyTarget().getThreadId());
        assertEquals("telegram:test_bot:chat:-200:thread:333", inbound.getPrincipal().getId());
    }

    @Test
    void ignoresTokenAndReasoningOutboundMessages() {
        RecordingTelegramApiClient apiClient = new RecordingTelegramApiClient(properties());
        TelegramInstanceAdapter adapter = adapter(apiClient, false);
        ReplyTarget replyTarget = ReplyTarget.builder().chatId("1001").messageId("11").build();

        adapter.send(OutboundMessage.builder().kind(OutboundMessage.Kind.TOKEN).replyTarget(replyTarget).text("a").build());
        adapter.send(OutboundMessage.builder().kind(OutboundMessage.Kind.REASONING).replyTarget(replyTarget).text("b").build());
        adapter.send(OutboundMessage.builder().kind(OutboundMessage.Kind.TEXT).replyTarget(replyTarget).text("done").build());

        assertEquals(1, apiClient.sentTexts);
        assertEquals("done", apiClient.lastText);
        assertEquals("1001", apiClient.lastTarget.getChatId());
    }

    @Test
    void openClarifyConsumesNextMatchingMessageAsAnswer() {
        RecordingTelegramApiClient apiClient = new RecordingTelegramApiClient(properties());
        TelegramInstanceAdapter adapter = adapter(apiClient, false);
        ClarifyPrompt prompt = ClarifyPrompt.builder()
                .requestId("clarify-1")
                .sessionId("session-1")
                .question("What should I prioritize?")
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.TELEGRAM)
                        .platformAccountId("test_bot")
                        .chatId("1001")
                        .senderId("1001")
                        .build())
                .replyTarget(ReplyTarget.builder().chatId("1001").messageId("11").build())
                .build();
        TelegramInstanceAdapter[] adapterRef = new TelegramInstanceAdapter[]{adapter};
        apiClient.onSendClarify = () -> new Thread(() -> adapterRef[0].normalizeInbound(
                update(101, message(12, 1001L, "Alice", Chat.Type.Private, 1001L, null, "maintainability", null)),
                ChannelAuthContext.builder().build())).start();

        ClarifyDecision decision = adapter.requestClarify(prompt);

        assertEquals("maintainability", decision.getAnswer());
        assertTrue(apiClient.awaitClarifyStatus("answered"));
        assertEquals("maintainability", apiClient.lastClarifyAnswer);
    }

    private TelegramInstanceAdapter adapter(RecordingTelegramApiClient apiClient, boolean mentionRequired) {
        TelegramProperties.InstanceProperties properties = properties();
        properties.setMentionRequiredInGroup(mentionRequired);
        return new TelegramInstanceAdapter(properties, apiClient, new ToolDisplayMessageRenderer());
    }

    private TelegramProperties.InstanceProperties properties() {
        TelegramProperties.InstanceProperties properties = new TelegramProperties.InstanceProperties();
        properties.setBotUsername("test_bot");
        properties.setApprovalTimeoutSeconds(5);
        return properties;
    }

    private Update update(int updateId, Message message) {
        Update update = new Update();
        set(update, "update_id", updateId);
        set(update, "message", message);
        return update;
    }

    private Message message(int messageId,
                            long senderId,
                            String firstName,
                            Chat.Type chatType,
                            long chatId,
                            Long threadId,
                            String text,
                            Message replyToMessage) {
        Message message = new Message();
        setInHierarchy(message, "message_id", messageId);
        set(message, "from", user(senderId, firstName, false, "user" + senderId));
        setInHierarchy(message, "chat", chat(chatId, chatType));
        set(message, "message_thread_id", threadId);
        set(message, "text", text);
        set(message, "reply_to_message", replyToMessage);
        return message;
    }

    private Chat chat(long id, Chat.Type type) {
        Chat chat = new Chat();
        set(chat, "id", id);
        set(chat, "type", type);
        return chat;
    }

    private User user(long id, String firstName, boolean bot, String username) {
        User user = new User(id);
        set(user, "first_name", firstName);
        set(user, "is_bot", bot);
        set(user, "username", username);
        return user;
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

    private void setInHierarchy(Object target, String fieldName, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Missing field: " + fieldName);
    }

    private static class RecordingTelegramApiClient extends TelegramApiClient {
        int sentTexts;
        String lastText;
        TelegramSendTarget lastTarget;
        Runnable onSendClarify;
        String lastClarifyStatus;
        String lastClarifyAnswer;

        RecordingTelegramApiClient(TelegramProperties.InstanceProperties properties) {
            super(properties, null);
        }

        @Override
        public void sendText(TelegramSendTarget target, String text) {
            sentTexts++;
            lastTarget = target;
            lastText = text;
        }

        @Override
        public Integer sendClarifyMessage(TelegramSendTarget target, ClarifyPrompt prompt, java.util.List<String> callbackData) {
            if (onSendClarify != null) {
                onSendClarify.run();
            }
            return 99;
        }

        @Override
        public void editClarifyMessage(String chatId, Integer messageId, ClarifyPrompt prompt, String status, String answer) {
            lastClarifyStatus = status;
            lastClarifyAnswer = answer;
        }

        boolean awaitClarifyStatus(String status) {
            try {
                org.awaitility.Awaitility.await()
                        .atMost(Duration.ofSeconds(1))
                        .until(() -> status.equals(lastClarifyStatus));
                return true;
            } catch (org.awaitility.core.ConditionTimeoutException e) {
                return false;
            }
        }
    }
}
