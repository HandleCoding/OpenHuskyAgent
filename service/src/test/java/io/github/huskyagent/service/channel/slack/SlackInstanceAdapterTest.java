package io.github.huskyagent.service.channel.slack;

import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
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

import static org.junit.jupiter.api.Assertions.*;

class SlackInstanceAdapterTest {

    @Test
    void normalizesDirectMessage() {
        RecordingSlackApiClient apiClient = new RecordingSlackApiClient(properties());
        SlackInstanceAdapter adapter = adapter(apiClient, true);

        InboundMessage inbound = adapter.normalizeMessage(
                message("C1", "U123", "1710000000.000100", null, "hello", "im"),
                ChannelAuthContext.builder().connectionId("c1").build(),
                "Ev1");

        assertFalse(inbound.isIgnored());
        assertEquals("hello", inbound.getText());
        assertEquals("slack:U999:U123", inbound.getPrincipal().getId());
        assertEquals(ConversationType.DIRECT, inbound.getChannelIdentity().getConversationType());
        assertEquals("U999", inbound.getChannelIdentity().getPlatformAccountId());
        assertEquals("C1", inbound.getChannelIdentity().getChatId());
        assertEquals("1710000000.000100", inbound.getReplyTarget().getMessageId());
        assertNull(inbound.getSceneId());
    }

    @Test
    void ignoresUnmentionedChannelMessageWhenMentionRequired() {
        RecordingSlackApiClient apiClient = new RecordingSlackApiClient(properties());
        SlackInstanceAdapter adapter = adapter(apiClient, true);

        InboundMessage inbound = adapter.normalizeMessage(
                message("C1", "U123", "1710000000.000100", null, "hello", "channel"),
                ChannelAuthContext.builder().build(),
                "Ev1");

        assertTrue(inbound.isIgnored());
    }

    @Test
    void acceptsAppMentionAndStripsMention() {
        RecordingSlackApiClient apiClient = new RecordingSlackApiClient(properties());
        SlackInstanceAdapter adapter = adapter(apiClient, true);

        InboundMessage inbound = adapter.normalizeAppMention(
                appMention("C1", "U123", "1710000000.000100", null, "<@U999> help me"),
                ChannelAuthContext.builder().build(),
                "Ev1");

        assertFalse(inbound.isIgnored());
        assertEquals("help me", inbound.getText());
        assertEquals(ConversationType.GROUP, inbound.getChannelIdentity().getConversationType());
        assertEquals("slack:U999:channel:C1:thread:1710000000.000100", inbound.getPrincipal().getId());
    }

    @Test
    void mapsThreadReplyToThreadConversation() {
        RecordingSlackApiClient apiClient = new RecordingSlackApiClient(properties());
        SlackInstanceAdapter adapter = adapter(apiClient, true);

        InboundMessage inbound = adapter.normalizeMessage(
                message("C1", "U123", "1710000000.000200", "1710000000.000100", "<@U999> thread help", "channel"),
                ChannelAuthContext.builder().build(),
                "Ev1");

        assertFalse(inbound.isIgnored());
        assertEquals(ConversationType.THREAD, inbound.getChannelIdentity().getConversationType());
        assertEquals("1710000000.000100", inbound.getChannelIdentity().getThreadId());
        assertEquals("1710000000.000100", inbound.getReplyTarget().getThreadId());
    }

    @Test
    void ignoresBotSubtypeAndSelfMessages() {
        RecordingSlackApiClient apiClient = new RecordingSlackApiClient(properties());
        SlackInstanceAdapter adapter = adapter(apiClient, false);
        MessageEvent botMessage = message("C1", "U123", "1", null, "hello", "channel");
        botMessage.setBotId("B1");
        MessageEvent selfMessage = message("C1", "U999", "2", null, "hello", "channel");

        assertTrue(adapter.normalizeMessage(botMessage, ChannelAuthContext.builder().build(), "Ev1").isIgnored());
        assertTrue(adapter.normalizeMessage(selfMessage, ChannelAuthContext.builder().build(), "Ev2").isIgnored());
    }

    @Test
    void ignoresTokenAndReasoningOutboundMessages() {
        RecordingSlackApiClient apiClient = new RecordingSlackApiClient(properties());
        SlackInstanceAdapter adapter = adapter(apiClient, false);
        ReplyTarget replyTarget = ReplyTarget.builder().chatId("C1").threadId("1710000000.000100").messageId("1710000000.000200").build();

        adapter.send(OutboundMessage.builder().kind(OutboundMessage.Kind.TOKEN).replyTarget(replyTarget).text("a").build());
        adapter.send(OutboundMessage.builder().kind(OutboundMessage.Kind.REASONING).replyTarget(replyTarget).text("b").build());
        adapter.send(OutboundMessage.builder().kind(OutboundMessage.Kind.TEXT).replyTarget(replyTarget).text("done").build());

        assertEquals(1, apiClient.sentTexts);
        assertEquals("done", apiClient.lastText);
        assertEquals("C1", apiClient.lastTarget.getChannelId());
        assertEquals("1710000000.000100", apiClient.lastTarget.getThreadTs());
    }

    @Test
    void openClarifyConsumesNextMatchingMessageAsAnswer() {
        RecordingSlackApiClient apiClient = new RecordingSlackApiClient(properties());
        SlackInstanceAdapter adapter = adapter(apiClient, false);
        ClarifyPrompt prompt = ClarifyPrompt.builder()
                .requestId("clarify-1")
                .sessionId("session-1")
                .question("What should I prioritize?")
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.SLACK)
                        .platformAccountId("U999")
                        .chatId("C1")
                        .senderId("U123")
                        .build())
                .replyTarget(ReplyTarget.builder().chatId("C1").threadId("1710000000.000100").messageId("1710000000.000100").build())
                .build();
        apiClient.onSendClarify = () -> new Thread(() -> adapter.normalizeMessage(
                message("C1", "U123", "1710000000.000200", "1710000000.000100", "maintainability", "channel"),
                ChannelAuthContext.builder().build(),
                "Ev2")).start();

        ClarifyDecision decision = adapter.requestClarify(prompt);

        assertEquals("maintainability", decision.getAnswer());
        assertEquals("answered", apiClient.lastClarifyStatus);
        assertEquals("maintainability", apiClient.lastClarifyAnswer);
    }

    @Test
    void openClarifyConsumesUnmentionedThreadReplyWhenMentionRequired() {
        RecordingSlackApiClient apiClient = new RecordingSlackApiClient(properties());
        SlackInstanceAdapter adapter = adapter(apiClient, true);
        ClarifyPrompt prompt = ClarifyPrompt.builder()
                .requestId("clarify-1")
                .sessionId("session-1")
                .question("What should I prioritize?")
                .channelIdentity(ChannelIdentity.builder()
                        .channelType(ChannelType.SLACK)
                        .platformAccountId("U999")
                        .chatId("C1")
                        .senderId("U123")
                        .build())
                .replyTarget(ReplyTarget.builder().chatId("C1").threadId("1710000000.000100").messageId("1710000000.000100").build())
                .build();
        apiClient.onSendClarify = () -> new Thread(() -> adapter.normalizeMessage(
                message("C1", "U123", "1710000000.000200", "1710000000.000100", "maintainability", "channel"),
                ChannelAuthContext.builder().build(),
                "Ev2")).start();

        ClarifyDecision decision = adapter.requestClarify(prompt);

        assertEquals("maintainability", decision.getAnswer());
        assertEquals("answered", apiClient.lastClarifyStatus);
    }

    @Test
    void usesChannelTimestampAsMessageIdToDedupeAppMentionAndMessageEvents() {
        RecordingSlackApiClient apiClient = new RecordingSlackApiClient(properties());
        SlackInstanceAdapter adapter = adapter(apiClient, true);

        InboundMessage appMention = adapter.normalizeAppMention(
                appMention("C1", "U123", "1710000000.000100", null, "<@U999> help me"),
                ChannelAuthContext.builder().build(),
                "Ev-app-mention");
        InboundMessage message = adapter.normalizeMessage(
                message("C1", "U123", "1710000000.000100", null, "<@U999> help me", "channel"),
                ChannelAuthContext.builder().build(),
                "Ev-message");

        assertEquals("C1:1710000000.000100", appMention.getMessageId());
        assertEquals(appMention.getMessageId(), message.getMessageId());
    }

    private SlackInstanceAdapter adapter(RecordingSlackApiClient apiClient, boolean mentionRequired) {
        SlackProperties.InstanceProperties properties = properties();
        properties.setMentionRequiredInChannel(mentionRequired);
        return new SlackInstanceAdapter(properties, apiClient, new ToolDisplayMessageRenderer());
    }

    private SlackProperties.InstanceProperties properties() {
        SlackProperties.InstanceProperties properties = new SlackProperties.InstanceProperties();
        properties.setBotUserId("U999");
        properties.setApprovalTimeoutSeconds(5);
        return properties;
    }

    private MessageEvent message(String channel, String user, String ts, String threadTs, String text, String channelType) {
        MessageEvent event = new MessageEvent();
        event.setChannel(channel);
        event.setUser(user);
        event.setTs(ts);
        event.setThreadTs(threadTs);
        event.setEventTs(ts);
        event.setText(text);
        event.setChannelType(channelType);
        return event;
    }

    private AppMentionEvent appMention(String channel, String user, String ts, String threadTs, String text) {
        AppMentionEvent event = new AppMentionEvent();
        event.setChannel(channel);
        event.setUser(user);
        event.setTs(ts);
        event.setThreadTs(threadTs);
        event.setEventTs(ts);
        event.setText(text);
        return event;
    }

    private static class RecordingSlackApiClient extends SlackApiClient {
        int sentTexts;
        String lastText;
        SlackSendTarget lastTarget;
        String lastClarifyStatus;
        String lastClarifyAnswer;
        Runnable onSendClarify;

        RecordingSlackApiClient(SlackProperties.InstanceProperties properties) {
            super(properties, null);
        }

        @Override
        public SlackSentMessage sendText(SlackSendTarget target, String text) {
            sentTexts++;
            lastTarget = target;
            lastText = text;
            return new SlackSentMessage(target.getChannelId(), "1710000000.000300");
        }

        @Override
        public SlackSentMessage sendClarifyMessage(SlackSendTarget target, ClarifyPrompt prompt, java.util.List<String> callbackData) {
            if (onSendClarify != null) {
                onSendClarify.run();
            }
            return new SlackSentMessage(target.getChannelId(), "1710000000.000300");
        }

        @Override
        public void editClarifyMessage(String channelId, String ts, ClarifyPrompt prompt, String status, String answer) {
            lastClarifyStatus = status;
            lastClarifyAnswer = answer;
        }
    }
}
