package io.github.huskyagent.service.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import io.github.huskyagent.application.channel.runtime.SessionRoute;
import io.github.huskyagent.application.channel.runtime.ToolDisplayEvent;
import io.github.huskyagent.application.channel.runtime.ToolDisplayMessageRenderer;
import io.github.huskyagent.application.channel.runtime.ToolDisplayStatus;
import io.github.huskyagent.infra.channel.ApprovalDecision;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.ClarifyDecision;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.InboundContentPart;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.ReplyTarget;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuInstanceAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizesDirectTextMessage() throws Exception {
        FeishuInstanceAdapter adapter = adapter(false);
        JsonNode event = event("p2p", "hello", null, false);

        InboundMessage inbound = adapter.normalizeInbound(event, ChannelAuthContext.builder().connectionId("c1").build());

        assertFalse(inbound.isIgnored());
        assertEquals("hello", inbound.getText());
        assertEquals("feishu:cli_test:ou_user", inbound.getPrincipal().getId());
        assertEquals(ConversationType.DIRECT, inbound.getChannelIdentity().getConversationType());
        assertEquals("cli_test", inbound.getChannelIdentity().getPlatformAccountId());
        assertEquals("oc_chat", inbound.getChannelIdentity().getChatId());
        assertEquals("om_1", inbound.getReplyTarget().getMessageId());
        assertEquals("feishu-qa", inbound.getSceneId());
    }

    @Test
    void normalizesSdkMessageEvent() {
        FeishuInstanceAdapter adapter = adapter(false);
        P2MessageReceiveV1 event = sdkEvent("p2p", "hello sdk", null);

        InboundMessage inbound = adapter.normalizeInbound(event, ChannelAuthContext.builder().connectionId("c1").build());

        assertFalse(inbound.isIgnored());
        assertEquals("hello sdk", inbound.getText());
        assertEquals("feishu:cli_test:ou_user", inbound.getPrincipal().getId());
        assertEquals(ConversationType.DIRECT, inbound.getChannelIdentity().getConversationType());
        assertEquals("oc_chat", inbound.getChannelIdentity().getChatId());
        assertEquals("om_1", inbound.getReplyTarget().getMessageId());
    }
    @Test
    void ignoresUnmentionedGroupMessageWhenMentionRequired() throws Exception {
        FeishuInstanceAdapter adapter = adapter(true);
        JsonNode event = event("group", "hello", null, false);

        InboundMessage inbound = adapter.normalizeInbound(event, ChannelAuthContext.builder().build());

        assertTrue(inbound.isIgnored());
    }

    @Test
    void acceptsMentionedGroupMessageAndStripsMention() throws Exception {
        FeishuInstanceAdapter adapter = adapter(true);
        JsonNode event = event("group", "@ou_bot help me", null, false, true);

        InboundMessage inbound = adapter.normalizeInbound(event, ChannelAuthContext.builder().build());

        assertFalse(inbound.isIgnored());
        assertEquals("help me", inbound.getText());
        assertEquals("feishu:cli_test:chat:oc_chat", inbound.getPrincipal().getId());
        assertEquals(ConversationType.GROUP, inbound.getChannelIdentity().getConversationType());
    }

    @Test
    void groupSessionScopeUserKeepsSenderPrincipal() throws Exception {
        FeishuInstanceAdapter adapter = adapter(false, FeishuProperties.GroupSessionScope.USER);
        JsonNode event = event("group", "hello", null, false);

        InboundMessage inbound = adapter.normalizeInbound(event, ChannelAuthContext.builder().build());

        assertEquals("feishu:cli_test:ou_user", inbound.getPrincipal().getId());
    }

    @Test
    void groupSessionScopeChatIgnoresThreadInPrincipal() throws Exception {
        FeishuInstanceAdapter adapter = adapter(false, FeishuProperties.GroupSessionScope.CHAT);
        JsonNode event = event("group", "hello", "omt_thread", false);

        InboundMessage inbound = adapter.normalizeInbound(event, ChannelAuthContext.builder().build());

        assertEquals("feishu:cli_test:chat:oc_chat", inbound.getPrincipal().getId());
    }

    @Test
    void mapsThreadIdentity() throws Exception {
        FeishuInstanceAdapter adapter = adapter(false);
        JsonNode event = event("group", "hello", "omt_thread", false);

        InboundMessage inbound = adapter.normalizeInbound(event, ChannelAuthContext.builder().build());

        assertEquals(ConversationType.THREAD, inbound.getChannelIdentity().getConversationType());
        assertEquals("feishu:cli_test:chat:omt_thread", inbound.getPrincipal().getId());
        assertEquals("omt_thread", inbound.getChannelIdentity().getThreadId());
        assertEquals("omt_thread", inbound.getReplyTarget().getThreadId());
    }

    @Test
    void normalizesDirectImageMessage() throws Exception {
        FeishuApiClient apiClient = mock(FeishuApiClient.class);
        when(apiClient.getUserDisplayName("ou_user")).thenReturn(null);
        when(apiClient.downloadImage(eq("om_1"), eq("img_1"))).thenReturn(
                new FeishuApiClient.DownloadedResource(new byte[]{1, 2}, "image/png", "img_1.png", 2));
        FeishuInstanceAdapter adapter = adapter(false, apiClient);
        JsonNode event = imageEvent("p2p", "img_1", null, false);

        InboundMessage inbound = adapter.normalizeInbound(event, ChannelAuthContext.builder().build());

        assertFalse(inbound.isIgnored());
        assertEquals("", inbound.getText());
        assertEquals(1, inbound.getContentParts().size());
        assertEquals(InboundContentPart.Kind.IMAGE, inbound.getContentParts().get(0).getKind());
        assertEquals("image/png", inbound.getContentParts().get(0).getAttachment().getMimeType());
    }

    @Test
    void normalizesPostWithTextAndImage() throws Exception {
        FeishuApiClient apiClient = mock(FeishuApiClient.class);
        when(apiClient.getUserDisplayName("ou_user")).thenReturn(null);
        when(apiClient.downloadImage(eq("om_1"), eq("img_1"))).thenReturn(
                new FeishuApiClient.DownloadedResource(new byte[]{3}, "image/jpeg", "img_1.jpg", 1));
        FeishuInstanceAdapter adapter = adapter(false, apiClient);
        JsonNode event = postEvent("p2p", "look", "img_1", null, false);

        InboundMessage inbound = adapter.normalizeInbound(event, ChannelAuthContext.builder().build());

        assertFalse(inbound.isIgnored());
        assertEquals("look", inbound.getText());
        assertEquals(2, inbound.getContentParts().size());
        assertEquals(InboundContentPart.Kind.TEXT, inbound.getContentParts().get(0).getKind());
        assertEquals(InboundContentPart.Kind.IMAGE, inbound.getContentParts().get(1).getKind());
    }

    @Test
    void ignoresUnmentionedGroupImageWhenMentionRequired() throws Exception {
        FeishuInstanceAdapter adapter = adapter(true);
        JsonNode event = imageEvent("group", "img_1", null, false);

        InboundMessage inbound = adapter.normalizeInbound(event, ChannelAuthContext.builder().build());

        assertTrue(inbound.isIgnored());
    }

    @Test
    void completesApprovalFromCardCallback() throws Exception {
        FeishuApiClient apiClient = mock(FeishuApiClient.class);
        FeishuProperties.InstanceProperties properties = new FeishuProperties.InstanceProperties();
        properties.setApprovalTimeoutSeconds(5);
        FeishuInstanceAdapter[] adapterRef = new FeishuInstanceAdapter[1];
        when(apiClient.sendApprovalCard(any(), any())).thenAnswer(invocation -> {
            adapterRef[0].completeApproval("approval-1", "always");
            return "om_card";
        });
        FeishuInstanceAdapter adapter = new FeishuInstanceAdapter(
                properties,
                
                apiClient,
                objectMapper,
                new ToolDisplayMessageRenderer()
        );
        adapterRef[0] = adapter;
        ApprovalPrompt prompt = ApprovalPrompt.builder()
                .requestId("approval-1")
                .sessionId("session-1")
                .toolName("terminal")
                .replyTarget(ReplyTarget.builder().chatId("oc_chat").messageId("om_1").build())
                .build();

        ApprovalDecision result = adapter.requestApproval(prompt);
        assertTrue(result.isApproved());
        assertTrue(result.isAlways());
    }

    @Test
    void completesClarifyFromCardCallback() throws Exception {
        FeishuApiClient apiClient = mock(FeishuApiClient.class);
        FeishuProperties.InstanceProperties properties = new FeishuProperties.InstanceProperties();
        properties.setApprovalTimeoutSeconds(5);
        FeishuInstanceAdapter[] adapterRef = new FeishuInstanceAdapter[1];
        when(apiClient.sendClarifyCard(any(), any())).thenAnswer(invocation -> {
            adapterRef[0].completeClarify("clarify-1", "Use option A");
            return "om_card";
        });
        FeishuInstanceAdapter adapter = new FeishuInstanceAdapter(
                properties,
                
                apiClient,
                objectMapper,
                new ToolDisplayMessageRenderer()
        );
        adapterRef[0] = adapter;
        ClarifyPrompt prompt = ClarifyPrompt.builder()
                .requestId("clarify-1")
                .sessionId("session-1")
                .question("Which option should I use?")
                .replyTarget(ReplyTarget.builder().chatId("oc_chat").messageId("om_1").build())
                .build();

        ClarifyDecision result = adapter.requestClarify(prompt);
        assertEquals("Use option A", result.getAnswer());
    }

    @Test
    void openClarifyConsumesNextMessageAsAnswer() throws Exception {
        FeishuApiClient apiClient = mock(FeishuApiClient.class);
        FeishuProperties.InstanceProperties properties = new FeishuProperties.InstanceProperties();
        properties.setAppId("cli_test");
        properties.setBotOpenId("ou_bot");
        properties.setApprovalTimeoutSeconds(5);
        properties.setMentionRequiredInGroup(false);
        FeishuInstanceAdapter[] adapterRef = new FeishuInstanceAdapter[1];
        when(apiClient.sendClarifyCard(any(), any())).thenAnswer(invocation -> {
            new Thread(() -> adapterRef[0].normalizeInbound(
                    event("p2p", "Please prioritize maintainability", null, false),
                    ChannelAuthContext.builder().build())).start();
            return "om_card";
        });
        FeishuInstanceAdapter adapter = new FeishuInstanceAdapter(
                properties,
                
                apiClient,
                objectMapper,
                new ToolDisplayMessageRenderer()
        );
        adapterRef[0] = adapter;
        ClarifyPrompt prompt = ClarifyPrompt.builder()
                .requestId("clarify-1")
                .sessionId("session-1")
                .question("What should I prioritize?")
                .channelIdentity(io.github.huskyagent.infra.channel.ChannelIdentity.builder()
                        .senderId("ou_user")
                        .build())
                .replyTarget(ReplyTarget.builder().chatId("oc_chat").messageId("om_1").build())
                .build();

        ClarifyDecision result = adapter.requestClarify(prompt);
        assertEquals("Please prioritize maintainability", result.getAnswer());
    }

    @Test
    void runtimeToolEventSendsToolStatusText() {
        FeishuApiClient apiClient = mock(FeishuApiClient.class);
        FeishuInstanceAdapter adapter = adapter(false, apiClient);
        SessionRoute route = route();

        adapter.onRuntimeEvent(route, new ToolDisplayEvent(
                "session-1", "call-1", "web_search", "query=test", null,
                ToolDisplayStatus.STARTED, 0, null, Instant.now()
        ));

        verify(apiClient).sendText(argThat(message ->
                "oc_chat".equals(message.getChatId())
                        && "om_1".equals(message.getMessageId())
                        && message.getText().contains("正在调用工具：web_search")
                        && message.getText().contains("参数：query=test")
        ));
    }

    @Test
    void runtimeToolEventDoesNotSendWhenDisabled() {
        FeishuApiClient apiClient = mock(FeishuApiClient.class);
        FeishuInstanceAdapter adapter = adapter(false, apiClient, false);

        adapter.onRuntimeEvent(route(), new ToolDisplayEvent(
                "session-1", "call-1", "web_search", "query=test", null,
                ToolDisplayStatus.STARTED, 0, null, Instant.now()
        ));

        verify(apiClient, never()).sendText(any());
    }

    private SessionRoute route() {
        return new SessionRoute(
                "session-1",
                io.github.huskyagent.infra.channel.ChannelType.FEISHU,
                io.github.huskyagent.infra.channel.ChannelIdentity.builder()
                        .channelType(io.github.huskyagent.infra.channel.ChannelType.FEISHU)
                        .build(),
                ReplyTarget.builder().chatId("oc_chat").messageId("om_1").build(),
                "om_1"
        );
    }

    private JsonNode event(String chatType, String text, String threadId, boolean mentioned) {
        return event(chatType, text, threadId, true, mentioned);
    }

    private JsonNode event(String chatType, String text, String threadId, boolean includeMentionText, boolean mentioned) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode event = root.putObject("event");
        event.putObject("sender").putObject("sender_id").put("open_id", "ou_user");
        ObjectNode message = event.putObject("message");
        message.put("message_id", "om_1");
        message.put("chat_id", "oc_chat");
        message.put("chat_type", chatType);
        message.put("message_type", "text");
        String contentText = includeMentionText && mentioned ? "@ou_bot " + text : text;
        message.put("content", objectMapper.createObjectNode().put("text", contentText).toString());
        if (threadId != null) {
            message.put("thread_id", threadId);
        }
        if (mentioned) {
            message.putArray("mentions").addObject().putObject("id").put("open_id", "ou_bot");
        }
        return root;
    }

    private JsonNode imageEvent(String chatType, String imageKey, String threadId, boolean mentioned) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode event = root.putObject("event");
        event.putObject("sender").putObject("sender_id").put("open_id", "ou_user");
        ObjectNode message = event.putObject("message");
        message.put("message_id", "om_1");
        message.put("chat_id", "oc_chat");
        message.put("chat_type", chatType);
        message.put("message_type", "image");
        message.put("content", objectMapper.createObjectNode().put("image_key", imageKey).toString());
        if (threadId != null) {
            message.put("thread_id", threadId);
        }
        if (mentioned) {
            message.putArray("mentions").addObject().putObject("id").put("open_id", "ou_bot");
        }
        return root;
    }

    private JsonNode postEvent(String chatType, String text, String imageKey, String threadId, boolean mentioned) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode event = root.putObject("event");
        event.putObject("sender").putObject("sender_id").put("open_id", "ou_user");
        ObjectNode message = event.putObject("message");
        message.put("message_id", "om_1");
        message.put("chat_id", "oc_chat");
        message.put("chat_type", chatType);
        message.put("message_type", "post");
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode row = content.putObject("zh_cn").putArray("content").addArray();
        row.addObject().put("tag", "text").put("text", text);
        row.addObject().put("tag", "img").put("image_key", imageKey);
        message.put("content", content.toString());
        if (threadId != null) {
            message.put("thread_id", threadId);
        }
        if (mentioned) {
            message.putArray("mentions").addObject().putObject("id").put("open_id", "ou_bot");
        }
        return root;
    }

    private P2MessageReceiveV1 sdkEvent(String chatType, String text, String threadId) {
        P2MessageReceiveV1 event = new P2MessageReceiveV1();
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setSender(EventSender.newBuilder()
                .senderId(UserId.newBuilder().openId("ou_user").build())
                .build());
        data.setMessage(EventMessage.newBuilder()
                .messageId("om_1")
                .chatId("oc_chat")
                .chatType(chatType)
                .messageType("text")
                .threadId(threadId)
                .content(objectMapper.createObjectNode().put("text", text).toString())
                .build());
        event.setEvent(data);
        return event;
    }

    private FeishuInstanceAdapter adapter(boolean mentionRequired) {
        return adapter(mentionRequired, FeishuProperties.GroupSessionScope.THREAD);
    }

    private FeishuInstanceAdapter adapter(boolean mentionRequired, FeishuProperties.GroupSessionScope groupSessionScope) {
        FeishuProperties.InstanceProperties properties = new FeishuProperties.InstanceProperties();
        properties.setAppId("cli_test");
        properties.setBotOpenId("ou_bot");
        properties.setDefaultScene("feishu-qa");
        properties.setMentionRequiredInGroup(mentionRequired);
        properties.setGroupSessionScope(groupSessionScope);
        properties.setEnabled(true);
        return new FeishuInstanceAdapter(
                properties,
                
                new FeishuApiClient(properties, objectMapper, new FeishuCardRenderer(objectMapper)),
                objectMapper,
                new ToolDisplayMessageRenderer()
        );
    }

    private FeishuInstanceAdapter adapter(boolean mentionRequired, FeishuApiClient apiClient) {
        return adapter(mentionRequired, apiClient, true);
    }

    private FeishuInstanceAdapter adapter(boolean mentionRequired, FeishuApiClient apiClient, boolean showToolCalls) {
        FeishuProperties.InstanceProperties properties = new FeishuProperties.InstanceProperties();
        properties.setAppId("cli_test");
        properties.setBotOpenId("ou_bot");
        properties.setDefaultScene("feishu-qa");
        properties.setMentionRequiredInGroup(mentionRequired);
        properties.setShowToolCalls(showToolCalls);
        properties.setEnabled(true);
        return new FeishuInstanceAdapter(
                properties,
                
                apiClient,
                objectMapper,
                new ToolDisplayMessageRenderer()
        );
    }
}
