package io.github.huskyagent.service.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import io.github.huskyagent.application.channel.AbstractChannelAdapter;
import io.github.huskyagent.application.channel.runtime.RuntimeEvent;
import io.github.huskyagent.application.channel.runtime.SessionRoute;
import io.github.huskyagent.application.channel.runtime.ToolDisplayEvent;
import io.github.huskyagent.application.channel.runtime.ToolDisplayMessageRenderer;
import io.github.huskyagent.application.channel.ChannelInboundContent;
import io.github.huskyagent.application.channel.ChannelMediaReference;
import io.github.huskyagent.application.channel.MultimodalChannelAdapter;
import io.github.huskyagent.infra.channel.ApprovalDecision;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ChannelAuthContext;
import io.github.huskyagent.infra.channel.ChannelCapabilities;
import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import io.github.huskyagent.infra.channel.ClarifyDecision;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import io.github.huskyagent.infra.channel.ConversationType;
import io.github.huskyagent.infra.channel.InboundContentPart;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.MessageAttachment;
import io.github.huskyagent.infra.channel.OutboundMessage;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.channel.ReplyTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class FeishuInstanceAdapter extends AbstractChannelAdapter implements MultimodalChannelAdapter {

    private final FeishuProperties.InstanceProperties properties;
    private final FeishuApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final ToolDisplayMessageRenderer toolDisplayMessageRenderer;
    private final Map<String, String> processingReactions = new ConcurrentHashMap<>();
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, PendingClarify> pendingClarifications = new ConcurrentHashMap<>();

    @Override
    public ChannelType channelType() {
        return ChannelType.FEISHU;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.builder()
                .requiresMentionInGroup(properties.isMentionRequiredInGroup())
                .supportsApprovalCard(true)
                .supportsImageInput(true)
                .supportsEdit(true)
                .supportsStreaming(false)
                .supportsTyping(false)
                .build();
    }

    @Override
    public InboundMessage normalizeInbound(Object rawEvent, ChannelAuthContext authContext) {
        if (rawEvent instanceof P2MessageReceiveV1 event) {
            return normalizeMessageReceive(event, authContext);
        }
        JsonNode root = toJsonNode(rawEvent);
        JsonNode event = root.path("event");
        JsonNode message = event.path("message");
        if (message.isMissingNode() || message.isNull()) {
            return InboundMessage.ignored(rawEvent);
        }

        String messageType = text(message, "message_type");

        String chatType = text(message, "chat_type");
        ConversationType conversationType = toConversationType(chatType, text(message, "thread_id"), text(message, "root_id"));
        boolean groupLike = conversationType == ConversationType.GROUP || conversationType == ConversationType.THREAD;

        String messageId = text(message, "message_id");
        String content = text(message, "content");
        ChannelInboundContent inboundContent = parseContent(messageType, content, messageId);
        if (inboundContent.isIgnored()) {
            return InboundMessage.ignored(rawEvent);
        }
        String normalizedText = stripBotMention(inboundContent.getText()).trim();
        List<InboundContentPart> parts = buildParts(normalizedText, inboundContent.getMediaRefs());
        if (parts.isEmpty() && normalizedText.isBlank()) {
            return InboundMessage.ignored(rawEvent);
        }

        String openId = text(event.path("sender").path("sender_id"), "open_id");
        String senderId = openId != null ? openId : text(event.path("sender").path("sender_id"), "user_id");
        String chatId = text(message, "chat_id");
        String threadId = firstNonBlank(text(message, "thread_id"), text(message, "root_id"));
        if (completePendingOpenClarify(chatId, threadId, senderId, normalizedText)) {
            return InboundMessage.ignored(rawEvent);
        }

        if (groupLike && properties.isMentionRequiredInGroup() && !mentionsBot(message)) {
            return InboundMessage.ignored(rawEvent);
        }

        return buildInbound(new FeishuInboundContext(
                rawEvent,
                authContext,
                normalizedText,
                parts,
                senderId,
                chatId,
                messageId,
                threadId,
                conversationType,
                chatType));
    }

    @Override
    public ChannelInboundContent normalizeContent(Object rawEvent, ChannelAuthContext authContext) {
        if (rawEvent instanceof P2MessageReceiveV1 event) {
            EventMessage message = event.getEvent() != null ? event.getEvent().getMessage() : null;
            return message != null
                    ? parseContent(message.getMessageType(), message.getContent(), message.getMessageId())
                    : ChannelInboundContent.ignored();
        }
        JsonNode root = toJsonNode(rawEvent);
        JsonNode message = root.path("event").path("message");
        if (message.isMissingNode() || message.isNull()) {
            return ChannelInboundContent.ignored();
        }
        return parseContent(text(message, "message_type"), text(message, "content"), text(message, "message_id"));
    }

    @Override
    public List<MessageAttachment> downloadAttachments(List<ChannelMediaReference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        List<MessageAttachment> attachments = new ArrayList<>();
        for (ChannelMediaReference reference : references) {
            if (reference == null || reference.getKind() != InboundContentPart.Kind.IMAGE) {
                continue;
            }
            FeishuApiClient.DownloadedResource resource = apiClient.downloadImage(
                    reference.getMessageId(), reference.getResourceKey());
            if (resource == null) {
                continue;
            }
            attachments.add(MessageAttachment.builder()
                    .id(reference.getResourceKey())
                    .kind(MessageAttachment.Kind.IMAGE)
                    .mimeType(resource.mimeType())
                    .filename(resource.filename())
                    .sizeBytes(resource.sizeBytes())
                    .data(resource.data())
                    .metadata(reference.getMetadata())
                    .build());
        }
        return List.copyOf(attachments);
    }

    @Override
    public void send(OutboundMessage message) {
        if (message.getKind() == OutboundMessage.Kind.TOKEN || message.getKind() == OutboundMessage.Kind.REASONING) {
            return;
        }
        if (message.getText() == null || message.getText().isBlank()) {
            return;
        }
        apiClient.sendText(toSendTarget(message));
    }

    @Override
    public void onRuntimeEvent(SessionRoute route, RuntimeEvent event) {
        if (!properties.isShowToolCalls()) {
            return;
        }
        if (!(event instanceof ToolDisplayEvent toolEvent)) {
            return;
        }
        String text = toolDisplayMessageRenderer.render(toolEvent);
        if (text == null || text.isBlank()) {
            return;
        }
        send(OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TOOL_STATUS)
                .sessionId(route.sessionId())
                .channelIdentity(route.channelIdentity())
                .replyTarget(route.replyTarget())
                .text(text)
                .metadata(Map.of(
                        "eventType", "tool",
                        "status", toolEvent.status().name().toLowerCase(),
                        "toolName", toolEvent.toolName()
                ))
                .build());
    }

    @Override
    public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
        if (prompt == null || prompt.getRequestId() == null || prompt.getRequestId().isBlank()) {
            return ApprovalDecision.deny("Invalid approval request");
        }
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        pendingApprovals.put(prompt.getRequestId(), new PendingApproval(prompt, future, null));
        FeishuApiClient.FeishuSendTarget target = FeishuApiClient.FeishuSendTarget.builder()
                .chatId(prompt.getReplyTarget() != null ? prompt.getReplyTarget().getChatId() : null)
                .threadId(prompt.getReplyTarget() != null ? prompt.getReplyTarget().getThreadId() : null)
                .messageId(prompt.getReplyTarget() != null ? prompt.getReplyTarget().getMessageId() : null)
                .build();
        log.info("Sending Feishu approval card: requestId={}, sessionId={}, tool={}, chatId={}, messageId={}",
                prompt.getRequestId(), prompt.getSessionId(), prompt.getToolName(), target.getChatId(), target.getMessageId());
        String cardMessageId = apiClient.sendApprovalCard(target, prompt);
        if (cardMessageId == null || cardMessageId.isBlank()) {
            pendingApprovals.remove(prompt.getRequestId());
            log.warn("Failed to send Feishu approval card: requestId={}, sessionId={}, tool={}",
                    prompt.getRequestId(), prompt.getSessionId(), prompt.getToolName());
            return ApprovalDecision.deny("Failed to send Feishu approval card");
        }
        pendingApprovals.computeIfPresent(prompt.getRequestId(), (key, pending) ->
                pending.future() == future ? new PendingApproval(prompt, future, cardMessageId) : pending);
        try {
            return future.get(properties.getApprovalTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            PendingApproval pending = pendingApprovals.remove(prompt.getRequestId());
            if (pending != null) {
                apiClient.updateApprovalCard(pending.cardMessageId(), prompt, "timeout");
            }
            return ApprovalDecision.deny("Approval timed out");
        }
    }

    public boolean completeApproval(String requestId, String decision) {
        PendingApproval pending = pendingApprovals.remove(requestId);
        if (pending == null) {
            return false;
        }
        ApprovalDecision approvalDecision = switch (decision) {
            case "approve" -> ApprovalDecision.builder().approved(true).always(false).reason("Approved from Feishu").build();
            case "always" -> ApprovalDecision.builder().approved(true).always(true).reason("Always allowed from Feishu").build();
            default -> ApprovalDecision.deny("Rejected from Feishu");
        };
        pending.future().complete(approvalDecision);
        apiClient.updateApprovalCard(pending.cardMessageId(), pending.prompt(), approvalStatus(decision));
        return true;
    }

    @Override
    public ClarifyDecision requestClarify(ClarifyPrompt prompt) {
        if (prompt == null || prompt.getRequestId() == null || prompt.getRequestId().isBlank()) {
            return ClarifyDecision.answer("");
        }
        CompletableFuture<ClarifyDecision> future = new CompletableFuture<>();
        pendingClarifications.put(prompt.getRequestId(), new PendingClarify(prompt, future, null));
        FeishuApiClient.FeishuSendTarget target = FeishuApiClient.FeishuSendTarget.builder()
                .chatId(prompt.getReplyTarget() != null ? prompt.getReplyTarget().getChatId() : null)
                .threadId(prompt.getReplyTarget() != null ? prompt.getReplyTarget().getThreadId() : null)
                .messageId(prompt.getReplyTarget() != null ? prompt.getReplyTarget().getMessageId() : null)
                .build();
        log.info("Sending Feishu clarify card: requestId={}, sessionId={}, chatId={}, messageId={}",
                prompt.getRequestId(), prompt.getSessionId(), target.getChatId(), target.getMessageId());
        String cardMessageId = apiClient.sendClarifyCard(target, prompt);
        if (cardMessageId == null || cardMessageId.isBlank()) {
            pendingClarifications.remove(prompt.getRequestId());
            log.warn("Failed to send Feishu clarify card: requestId={}, sessionId={}",
                    prompt.getRequestId(), prompt.getSessionId());
            return ClarifyDecision.answer("");
        }
        pendingClarifications.computeIfPresent(prompt.getRequestId(), (key, pending) ->
                pending.future() == future ? new PendingClarify(prompt, future, cardMessageId) : pending);
        try {
            return future.get(properties.getApprovalTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            PendingClarify pending = pendingClarifications.remove(prompt.getRequestId());
            if (pending != null) {
                apiClient.updateClarifyCard(pending.cardMessageId(), prompt, "timeout", "");
            }
            return ClarifyDecision.answer("");
        }
    }

    public boolean completeClarify(String requestId, String answer) {
        PendingClarify pending = pendingClarifications.remove(requestId);
        if (pending == null) {
            return false;
        }
        ClarifyDecision decision = ClarifyDecision.answer(answer);
        pending.future().complete(decision);
        apiClient.updateClarifyCard(pending.cardMessageId(), pending.prompt(), "answered", decision.getAnswer());
        return true;
    }

    private boolean completePendingOpenClarify(String chatId, String threadId, String senderId, String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        for (Map.Entry<String, PendingClarify> entry : pendingClarifications.entrySet()) {
            ClarifyPrompt prompt = entry.getValue().prompt();
            if (prompt.getOptions() != null && !prompt.getOptions().isEmpty()) {
                continue;
            }
            if (matchesClarifyReply(prompt, chatId, threadId, senderId)) {
                return completeClarify(entry.getKey(), answer);
            }
        }
        return false;
    }

    private boolean matchesClarifyReply(ClarifyPrompt prompt, String chatId, String threadId, String senderId) {
        if (prompt == null || prompt.getReplyTarget() == null || prompt.getChannelIdentity() == null) {
            return false;
        }
        if (!sameValue(prompt.getReplyTarget().getChatId(), chatId)) {
            return false;
        }
        String expectedThreadId = prompt.getReplyTarget().getThreadId();
        if (expectedThreadId != null && !expectedThreadId.isBlank() && !sameValue(expectedThreadId, threadId)) {
            return false;
        }
        String expectedSenderId = prompt.getChannelIdentity().getSenderId();
        return expectedSenderId == null || expectedSenderId.isBlank() || sameValue(expectedSenderId, senderId);
    }

    private boolean sameValue(String expected, String actual) {
        return expected != null && actual != null && expected.equals(actual);
    }

    private String approvalStatus(String decision) {
        return switch (decision) {
            case "approve" -> "approved";
            case "always" -> "always";
            default -> "rejected";
        };
    }

    @Override
    public void typing(OutboundMessage message) {
        if (message == null || message.getReplyTarget() == null || message.getMetadata() == null) {
            return;
        }
        String messageId = message.getReplyTarget().getMessageId();
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String status = String.valueOf(message.getMetadata().getOrDefault("status", ""));
        if ("started".equals(status)) {
            String reactionId = apiClient.addReaction(messageId, "Typing");
            if (reactionId != null && !reactionId.isBlank()) {
                processingReactions.put(messageId, reactionId);
            }
            return;
        }
        String reactionId = processingReactions.remove(messageId);
        boolean removed = reactionId == null || apiClient.removeReaction(messageId, reactionId);
        if ("failed".equals(status) && removed) {
            apiClient.addReaction(messageId, "CrossMark");
        } else if (reactionId != null && !removed) {
            processingReactions.put(messageId, reactionId);
        }
    }

    private InboundMessage normalizeMessageReceive(P2MessageReceiveV1 rawEvent, ChannelAuthContext authContext) {
        P2MessageReceiveV1Data event = rawEvent.getEvent();
        EventMessage message = event != null ? event.getMessage() : null;
        if (message == null) {
            return InboundMessage.ignored(rawEvent);
        }
        String messageType = message.getMessageType();

        String threadId = firstNonBlank(message.getThreadId(), message.getRootId());
        ConversationType conversationType = toConversationType(message.getChatType(), message.getThreadId(), message.getRootId());
        boolean groupLike = conversationType == ConversationType.GROUP || conversationType == ConversationType.THREAD;
        ChannelInboundContent inboundContent = parseContent(messageType, message.getContent(), message.getMessageId());
        if (inboundContent.isIgnored()) {
            return InboundMessage.ignored(rawEvent);
        }
        String content = stripBotMention(inboundContent.getText()).trim();
        List<InboundContentPart> parts = buildParts(content, inboundContent.getMediaRefs());
        if (parts.isEmpty() && content.isBlank()) {
            return InboundMessage.ignored(rawEvent);
        }

        String senderId = senderId(event.getSender());
        if (completePendingOpenClarify(message.getChatId(), threadId, senderId, content)) {
            return InboundMessage.ignored(rawEvent);
        }
        if (groupLike && properties.isMentionRequiredInGroup() && !mentionsBot(message.getMentions())) {
            return InboundMessage.ignored(rawEvent);
        }
        return buildInbound(new FeishuInboundContext(
                rawEvent,
                authContext,
                content,
                parts,
                senderId,
                message.getChatId(),
                message.getMessageId(),
                threadId,
                conversationType,
                message.getChatType()));
    }

    private InboundMessage buildInbound(FeishuInboundContext context) {
        String displayName = apiClient.getUserDisplayName(context.senderId());
        String principalId = principalId(context.senderId(), context.chatId(), context.threadId(), context.conversationType());
        Principal principal = Principal.builder()
                .id(principalId)
                .displayName(displayName != null ? displayName : context.senderId())
                .channelType(ChannelType.FEISHU)
                .build();
        ChannelIdentity channelIdentity = ChannelIdentity.builder()
                .channelType(ChannelType.FEISHU)
                .conversationType(context.conversationType())
                .platformAccountId(firstNonBlank(properties.getAppId(), properties.getBotOpenId()))
                .chatId(context.chatId())
                .threadId(context.threadId())
                .senderId(context.senderId())
                .connectionId(context.authContext() != null ? context.authContext().getConnectionId() : null)
                .build();
        ReplyTarget replyTarget = ReplyTarget.builder()
                .chatId(context.chatId())
                .threadId(context.threadId())
                .messageId(context.messageId())
                .metadata(Map.of("receiveIdType", "chat_id"))
                .build();

        return InboundMessage.builder()
                .messageId(context.messageId())
                .text(context.content())
                .contentParts(context.parts() != null ? context.parts() : List.of())
                .principal(principal)
                .channelIdentity(channelIdentity)
                // Legacy: sceneId from instance defaultScene — prefer channel-bindings configuration
                .sceneId(properties.getDefaultScene())
                .replyTarget(replyTarget)
                .rawPayload(context.rawEvent())
                .metadata(Map.of("feishuChatType", context.chatType() != null ? context.chatType() : ""))
                .build();
    }

    private record FeishuInboundContext(Object rawEvent,
                                         ChannelAuthContext authContext,
                                         String content,
                                         List<InboundContentPart> parts,
                                         String senderId,
                                         String chatId,
                                         String messageId,
                                         String threadId,
                                         ConversationType conversationType,
                                         String chatType) {
    }

    private record PendingApproval(ApprovalPrompt prompt,
                                   CompletableFuture<ApprovalDecision> future,
                                   String cardMessageId) {
    }

    private record PendingClarify(ClarifyPrompt prompt,
                                  CompletableFuture<ClarifyDecision> future,
                                  String cardMessageId) {
    }

    private JsonNode toJsonNode(Object rawEvent) {
        if (rawEvent instanceof JsonNode node) {
            return node;
        }
        return objectMapper.valueToTree(rawEvent);
    }

    private ChannelInboundContent parseContent(String messageType, String contentText, String messageId) {
        if (messageType == null || "text".equals(messageType)) {
            String text = parseTextContent(contentText);
            return ChannelInboundContent.builder()
                    .text(text)
                    .parts(text.isBlank() ? List.of() : List.of(InboundContentPart.text(text)))
                    .mediaRefs(List.of())
                    .build();
        }
        if ("image".equals(messageType)) {
            String imageKey = parseImageKey(contentText);
            if (imageKey == null) {
                return ChannelInboundContent.ignored();
            }
            return ChannelInboundContent.builder()
                    .text("")
                    .parts(List.of())
                    .mediaRefs(List.of(imageRef(messageId, imageKey)))
                    .build();
        }
        if ("post".equals(messageType)) {
            return parsePostContent(contentText, messageId);
        }
        return ChannelInboundContent.ignored();
    }

    private List<InboundContentPart> buildParts(String text, List<ChannelMediaReference> mediaRefs) {
        List<InboundContentPart> parts = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            parts.add(InboundContentPart.text(text));
        }
        List<MessageAttachment> attachments = downloadAttachments(mediaRefs);
        for (MessageAttachment attachment : attachments) {
            parts.add(InboundContentPart.attachment(attachment));
        }
        return List.copyOf(parts);
    }

    private String parseImageKey(String contentText) {
        if (contentText == null || contentText.isBlank()) {
            return null;
        }
        try {
            JsonNode content = objectMapper.readTree(contentText);
            return text(content, "image_key");
        } catch (Exception e) {
            log.warn("Failed to parse Feishu image content: {}", contentText, e);
            return null;
        }
    }

    private ChannelInboundContent parsePostContent(String contentText, String messageId) {
        if (contentText == null || contentText.isBlank()) {
            return ChannelInboundContent.ignored();
        }
        try {
            JsonNode root = objectMapper.readTree(contentText);
            List<String> texts = new ArrayList<>();
            List<ChannelMediaReference> refs = new ArrayList<>();
            collectPostParts(root.path("zh_cn"), messageId, texts, refs);
            if (texts.isEmpty() && refs.isEmpty()) {
                root.fields().forEachRemaining(entry -> collectPostParts(entry.getValue(), messageId, texts, refs));
            }
            String text = String.join("\n", texts).trim();
            return ChannelInboundContent.builder()
                    .text(text)
                    .parts(text.isBlank() ? List.of() : List.of(InboundContentPart.text(text)))
                    .mediaRefs(List.copyOf(refs))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse Feishu post content: {}", contentText, e);
            return ChannelInboundContent.ignored();
        }
    }

    private void collectPostParts(JsonNode node, String messageId, List<String> texts, List<ChannelMediaReference> refs) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            String tag = text(node, "tag");
            if ("text".equals(tag) || "a".equals(tag) || "at".equals(tag) || "md".equals(tag)) {
                String value = firstNonBlank(text(node, "text"), text(node, "user_name"));
                if (value != null && !value.isBlank()) {
                    texts.add(value);
                }
            }
            if ("img".equals(tag) || "image".equals(tag)) {
                String imageKey = firstNonBlank(text(node, "image_key"), text(node, "imageKey"));
                if (imageKey != null) {
                    refs.add(imageRef(messageId, imageKey));
                }
            }
            node.fields().forEachRemaining(entry -> collectPostParts(entry.getValue(), messageId, texts, refs));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectPostParts(child, messageId, texts, refs);
            }
        }
    }

    private ChannelMediaReference imageRef(String messageId, String imageKey) {
        return ChannelMediaReference.builder()
                .channelType(ChannelType.FEISHU)
                .messageId(messageId)
                .resourceKey(imageKey)
                .kind(InboundContentPart.Kind.IMAGE)
                .metadata(Map.of("feishuResourceType", "image"))
                .build();
    }

    private String parseTextContent(String contentText) {
        if (contentText == null || contentText.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readTree(contentText).path("text").asText("");
        } catch (Exception e) {
            log.warn("Failed to parse Feishu text content: {}", contentText, e);
            return "";
        }
    }

    private String parseTextContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }
        try {
            JsonNode content = contentNode.isTextual()
                    ? objectMapper.readTree(contentNode.asText())
                    : contentNode;
            return content.path("text").asText("");
        } catch (Exception e) {
            log.warn("Failed to parse Feishu text content: {}", contentNode, e);
            return "";
        }
    }

    private ConversationType toConversationType(String chatType, String threadId, String rootId) {
        if (threadId != null || rootId != null) {
            return ConversationType.THREAD;
        }
        if ("p2p".equals(chatType)) {
            return ConversationType.DIRECT;
        }
        return ConversationType.GROUP;
    }

    private String principalId(String senderId, String chatId, String threadId, ConversationType conversationType) {
        if (conversationType == ConversationType.DIRECT || properties.getGroupSessionScope() == FeishuProperties.GroupSessionScope.USER) {
            return "feishu:" + properties.getAppId() + ":" + senderId;
        }
        String conversationId = properties.getGroupSessionScope() == FeishuProperties.GroupSessionScope.CHAT
                ? chatId
                : firstNonBlank(threadId, chatId);
        return "feishu:" + properties.getAppId() + ":chat:" + conversationId;
    }

    private boolean mentionsBot(JsonNode message) {
        JsonNode mentions = message.path("mentions");
        if (!mentions.isArray()) {
            return false;
        }
        for (JsonNode mention : mentions) {
            String openId = text(mention.path("id"), "open_id");
            if (matchesBot(openId)) {
                return true;
            }
            String name = text(mention, "name");
            if (name != null && stripAt(name).equals(stripAt(properties.getBotOpenId()))) {
                return true;
            }
        }
        return false;
    }

    private boolean mentionsBot(MentionEvent[] mentions) {
        if (mentions == null) {
            return false;
        }
        for (MentionEvent mention : mentions) {
            UserId id = mention.getId();
            if (id != null && matchesBot(id.getOpenId())) {
                return true;
            }
            if (mention.getName() != null && stripAt(mention.getName()).equals(stripAt(properties.getBotOpenId()))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesBot(String openId) {
        return properties.getBotOpenId() != null && properties.getBotOpenId().equals(openId);
    }

    private String stripBotMention(String text) {
        if (text == null || properties.getBotOpenId() == null || properties.getBotOpenId().isBlank()) {
            return text != null ? text : "";
        }
        return text.replace("@" + properties.getBotOpenId(), "")
                .replace(properties.getBotOpenId(), "");
    }

    private String stripAt(String value) {
        return value == null ? null : value.replace("@", "");
    }

    private String senderId(EventSender sender) {
        UserId senderId = sender != null ? sender.getSenderId() : null;
        if (senderId == null) {
            return null;
        }
        return firstNonBlank(senderId.getOpenId(), senderId.getUserId());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node != null ? node.path(field) : null;
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private FeishuApiClient.FeishuSendTarget toSendTarget(OutboundMessage message) {
        String chatId = message.getReplyTarget() != null
                ? message.getReplyTarget().getChatId()
                : message.getChannelIdentity().getChatId();
        String threadId = message.getReplyTarget() != null
                ? message.getReplyTarget().getThreadId()
                : message.getChannelIdentity().getThreadId();
        String messageId = message.getReplyTarget() != null
                ? message.getReplyTarget().getMessageId()
                : null;
        return FeishuApiClient.FeishuSendTarget.builder()
                .chatId(chatId)
                .threadId(threadId)
                .messageId(messageId)
                .text(message.getText())
                .build();
    }
}
