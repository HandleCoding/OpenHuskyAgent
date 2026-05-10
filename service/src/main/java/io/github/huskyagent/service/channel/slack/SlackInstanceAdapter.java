package io.github.huskyagent.service.channel.slack;

import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.view.ViewState;
import io.github.huskyagent.application.channel.AbstractChannelAdapter;
import io.github.huskyagent.application.channel.runtime.RuntimeEvent;
import io.github.huskyagent.application.channel.runtime.SessionRoute;
import io.github.huskyagent.application.channel.runtime.ToolDisplayEvent;
import io.github.huskyagent.application.channel.runtime.ToolDisplayMessageRenderer;
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
import io.github.huskyagent.infra.channel.OutboundMessage;
import io.github.huskyagent.infra.channel.Principal;
import io.github.huskyagent.infra.channel.ReplyTarget;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
public class SlackInstanceAdapter extends AbstractChannelAdapter {

    private final SlackProperties.InstanceProperties properties;
    private final SlackApiClient apiClient;
    private final ToolDisplayMessageRenderer toolDisplayMessageRenderer;
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, String> approvalRequestByToken = new ConcurrentHashMap<>();
    private final Map<String, PendingClarify> pendingClarifications = new ConcurrentHashMap<>();
    private final Map<String, String> clarifyRequestByToken = new ConcurrentHashMap<>();
    private String platformAccountId;

    public SlackInstanceAdapter(SlackProperties.InstanceProperties properties,
                                SlackApiClient apiClient,
                                ToolDisplayMessageRenderer toolDisplayMessageRenderer) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.toolDisplayMessageRenderer = toolDisplayMessageRenderer;
        this.platformAccountId = SlackApiClient.normalizeBotUserId(properties.getBotUserId());
    }

    public String platformAccountId() {
        if (isBlank(platformAccountId)) {
            platformAccountId = SlackApiClient.normalizeBotUserId(apiClient.resolveBotUserId());
        }
        return platformAccountId;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.SLACK;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.builder()
                .supportsApprovalCard(true)
                .supportsEdit(true)
                .supportsTyping(properties.isSendTypingStatus())
                .supportsStreaming(false)
                .supportsImageInput(false)
                .requiresMentionInGroup(properties.isMentionRequiredInChannel())
                .build();
    }

    @Override
    public InboundMessage normalizeInbound(Object rawEvent, ChannelAuthContext authContext) {
        if (rawEvent instanceof AppMentionEvent event) {
            return normalizeAppMention(event, authContext, null);
        }
        if (rawEvent instanceof MessageEvent event) {
            return normalizeMessage(event, authContext, null);
        }
        return InboundMessage.ignored(rawEvent);
    }

    InboundMessage normalizeAppMention(AppMentionEvent event, ChannelAuthContext authContext, String eventId) {
        if (event == null || isBlank(event.getText())) {
            return InboundMessage.ignored(event);
        }
        return normalizeTextEvent(
                event,
                authContext,
                eventId,
                event.getText(),
                event.getChannel(),
                event.getUser(),
                event.getTs(),
                event.getThreadTs(),
                event.getEventTs(),
                "app_mention",
                false,
                true);
    }

    InboundMessage normalizeMessage(MessageEvent event, ChannelAuthContext authContext, String eventId) {
        if (event == null || isBlank(event.getText())) {
            return InboundMessage.ignored(event);
        }
        if (!isBlank(event.getSubtype()) || !isBlank(event.getBotId()) || sameValue(platformAccountId(), event.getUser())) {
            return InboundMessage.ignored(event);
        }
        boolean direct = "im".equalsIgnoreCase(event.getChannelType());
        return normalizeTextEvent(
                event,
                authContext,
                eventId,
                event.getText(),
                event.getChannel(),
                event.getUser(),
                event.getTs(),
                event.getThreadTs(),
                event.getEventTs(),
                "message",
                direct,
                false);
    }

    @Override
    public void send(OutboundMessage message) {
        if (message == null || message.getKind() == OutboundMessage.Kind.TOKEN || message.getKind() == OutboundMessage.Kind.REASONING) {
            return;
        }
        if (message.getText() == null || message.getText().isBlank()) {
            return;
        }
        apiClient.sendText(SlackApiClient.target(message.getReplyTarget(), properties.isReplyBroadcast()), message.getText());
    }

    @Override
    public void typing(OutboundMessage message) {
        if (!properties.isSendTypingStatus() || message == null || message.getMetadata() == null) {
            return;
        }
        String status = String.valueOf(message.getMetadata().getOrDefault("status", ""));
        if ("started".equals(status)) {
            apiClient.sendText(SlackApiClient.target(message.getReplyTarget(), properties.isReplyBroadcast()), "Working...");
        }
    }

    @Override
    public void onRuntimeEvent(SessionRoute route, RuntimeEvent event) {
        if (!properties.isShowToolCalls() || !(event instanceof ToolDisplayEvent toolEvent)) {
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
        if (prompt == null || isBlank(prompt.getRequestId())) {
            return ApprovalDecision.deny("Invalid approval request");
        }
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        String token = shortToken();
        approvalRequestByToken.put(token, prompt.getRequestId());
        pendingApprovals.put(prompt.getRequestId(), new PendingApproval(prompt, future, token, null, null));
        SlackApiClient.SlackSentMessage message = apiClient.sendApprovalMessage(
                SlackApiClient.target(prompt.getReplyTarget(), properties.isReplyBroadcast()),
                prompt,
                "a:" + token + ":approve",
                "a:" + token + ":always",
                "a:" + token + ":reject");
        if (message == null) {
            pendingApprovals.remove(prompt.getRequestId());
            approvalRequestByToken.remove(token);
            return ApprovalDecision.deny("Failed to send Slack approval message");
        }
        pendingApprovals.computeIfPresent(prompt.getRequestId(), (key, pending) ->
                pending.future() == future ? new PendingApproval(prompt, future, token, message.channelId(), message.ts()) : pending);
        try {
            return future.get(properties.getApprovalTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            PendingApproval pending = pendingApprovals.remove(prompt.getRequestId());
            approvalRequestByToken.remove(token);
            if (pending != null) {
                apiClient.editApprovalMessage(pending.channelId(), pending.messageTs(), prompt, "timed out");
            }
            return ApprovalDecision.deny("Approval timed out");
        }
    }

    public boolean completeApproval(String value, String senderId) {
        String[] parts = value != null ? value.split(":", 3) : new String[0];
        if (parts.length != 3 || !"a".equals(parts[0])) {
            return false;
        }
        String requestId = approvalRequestByToken.remove(parts[1]);
        PendingApproval pending = requestId != null ? pendingApprovals.remove(requestId) : null;
        if (pending == null) {
            return false;
        }
        if (!matchesSender(pending.prompt().getChannelIdentity(), senderId)) {
            pendingApprovals.put(requestId, pending);
            approvalRequestByToken.put(parts[1], requestId);
            return false;
        }
        ApprovalDecision approvalDecision = switch (parts[2]) {
            case "approve" -> ApprovalDecision.builder().approved(true).always(false).reason("Approved from Slack").build();
            case "always" -> ApprovalDecision.builder().approved(true).always(true).reason("Always allowed from Slack").build();
            default -> ApprovalDecision.deny("Rejected from Slack");
        };
        pending.future().complete(approvalDecision);
        apiClient.editApprovalMessage(pending.channelId(), pending.messageTs(), pending.prompt(), approvalStatus(parts[2]));
        return true;
    }

    @Override
    public ClarifyDecision requestClarify(ClarifyPrompt prompt) {
        if (prompt == null || isBlank(prompt.getRequestId())) {
            return ClarifyDecision.answer("");
        }
        CompletableFuture<ClarifyDecision> future = new CompletableFuture<>();
        String token = shortToken();
        clarifyRequestByToken.put(token, prompt.getRequestId());
        pendingClarifications.put(prompt.getRequestId(), new PendingClarify(prompt, future, token, null, null));
        List<String> callbacks = new ArrayList<>();
        if (prompt.getOptions() != null) {
            for (int i = 0; i < prompt.getOptions().size(); i++) {
                callbacks.add("c:" + token + ":" + i);
            }
        }
        SlackApiClient.SlackSentMessage message = apiClient.sendClarifyMessage(
                SlackApiClient.target(prompt.getReplyTarget(), properties.isReplyBroadcast()), prompt, callbacks);
        if (message == null) {
            pendingClarifications.remove(prompt.getRequestId());
            clarifyRequestByToken.remove(token);
            return ClarifyDecision.answer("");
        }
        pendingClarifications.computeIfPresent(prompt.getRequestId(), (key, pending) ->
                pending.future() == future ? new PendingClarify(prompt, future, token, message.channelId(), message.ts()) : pending);
        try {
            return future.get(properties.getApprovalTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            PendingClarify pending = pendingClarifications.remove(prompt.getRequestId());
            clarifyRequestByToken.remove(token);
            if (pending != null) {
                apiClient.editClarifyMessage(pending.channelId(), pending.messageTs(), prompt, "timed out", "");
            }
            return ClarifyDecision.answer("");
        }
    }

    public boolean completeClarify(String value, String senderId) {
        String[] parts = value != null ? value.split(":", 3) : new String[0];
        if (parts.length != 3 || !"c".equals(parts[0])) {
            return false;
        }
        String requestId = clarifyRequestByToken.remove(parts[1]);
        PendingClarify pending = requestId != null ? pendingClarifications.remove(requestId) : null;
        if (pending == null) {
            return false;
        }
        if (!matchesSender(pending.prompt().getChannelIdentity(), senderId)) {
            pendingClarifications.put(requestId, pending);
            clarifyRequestByToken.put(parts[1], requestId);
            return false;
        }
        String answer = clarifyAnswer(pending.prompt(), parts[2]);
        ClarifyDecision decision = ClarifyDecision.answer(answer);
        pending.future().complete(decision);
        apiClient.editClarifyMessage(pending.channelId(), pending.messageTs(), pending.prompt(), "answered", decision.getAnswer());
        return true;
    }

    boolean completePendingOpenClarify(String channelId, String threadTs, String senderId, String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        for (Map.Entry<String, PendingClarify> entry : pendingClarifications.entrySet()) {
            ClarifyPrompt prompt = entry.getValue().prompt();
            if (prompt.getOptions() != null && !prompt.getOptions().isEmpty()) {
                continue;
            }
            if (matchesClarifyReply(prompt, channelId, threadTs, senderId)) {
                String token = entry.getValue().token();
                pendingClarifications.remove(entry.getKey());
                clarifyRequestByToken.remove(token);
                entry.getValue().future().complete(ClarifyDecision.answer(answer));
                apiClient.editClarifyMessage(entry.getValue().channelId(), entry.getValue().messageTs(), prompt, "answered", answer);
                return true;
            }
        }
        return false;
    }

    private InboundMessage normalizeTextEvent(Object rawEvent,
                                              ChannelAuthContext authContext,
                                              String eventId,
                                              String rawText,
                                              String channelId,
                                              String userId,
                                              String ts,
                                              String threadTs,
                                              String eventTs,
                                              String eventType,
                                              boolean direct,
                                              boolean appMention) {
        if (isBlank(channelId) || isBlank(userId) || isBlank(ts)) {
            return InboundMessage.ignored(rawEvent);
        }
        String rootThreadTs = !isBlank(threadTs) ? threadTs : ts;
        ConversationType conversationType = direct ? ConversationType.DIRECT : (!isBlank(threadTs) ? ConversationType.THREAD : ConversationType.GROUP);
        String text = stripBotAddress(rawText.trim()).trim();
        if (completePendingOpenClarify(channelId, rootThreadTs, userId, text)) {
            return InboundMessage.ignored(rawEvent);
        }
        if (!direct && properties.isMentionRequiredInChannel() && !appMention && !addressesBot(rawText)) {
            return InboundMessage.ignored(rawEvent);
        }
        if (text.isBlank()) {
            return InboundMessage.ignored(rawEvent);
        }
        ChannelIdentity channelIdentity = ChannelIdentity.builder()
                .channelType(ChannelType.SLACK)
                .conversationType(conversationType)
                .platformAccountId(platformAccountId())
                .chatId(channelId)
                .threadId(rootThreadTs)
                .senderId(userId)
                .connectionId(authContext != null ? authContext.getConnectionId() : null)
                .build();
        ReplyTarget replyTarget = ReplyTarget.builder()
                .chatId(channelId)
                .threadId(rootThreadTs)
                .messageId(ts)
                .build();
        return InboundMessage.builder()
                .messageId(channelId + ":" + ts)
                .text(text)
                .contentParts(List.of(InboundContentPart.text(text)))
                .principal(Principal.builder()
                        .id(principalId(userId, channelId, rootThreadTs, conversationType))
                        .displayName(userId)
                        .channelType(ChannelType.SLACK)
                        .build())
                .channelIdentity(channelIdentity)
                .sceneId(properties.getDefaultScene())
                .replyTarget(replyTarget)
                .rawPayload(rawEvent)
                .metadata(Map.of("slackEventType", eventType))
                .build();
    }

    private boolean addressesBot(String text) {
        String botUserId = platformAccountId();
        return !isBlank(botUserId) && botMentionPattern(botUserId).matcher(text).find();
    }

    private String stripBotAddress(String text) {
        String botUserId = platformAccountId();
        if (isBlank(botUserId) || text == null) {
            return text != null ? text : "";
        }
        return botMentionPattern(botUserId).matcher(text).replaceAll("");
    }

    private Pattern botMentionPattern(String botUserId) {
        return Pattern.compile("<@" + Pattern.quote(botUserId) + "(?:\\|[^>]+)?>");
    }

    private String principalId(String senderId, String channelId, String threadTs, ConversationType conversationType) {
        String prefix = "slack:" + platformAccountId() + ":";
        if (conversationType == ConversationType.DIRECT || properties.getGroupSessionScope() == SlackProperties.GroupSessionScope.USER) {
            return prefix + senderId;
        }
        if (properties.getGroupSessionScope() == SlackProperties.GroupSessionScope.CHANNEL) {
            return prefix + "channel:" + channelId;
        }
        return prefix + "channel:" + channelId + ":thread:" + (!isBlank(threadTs) ? threadTs : "main");
    }

    private boolean matchesClarifyReply(ClarifyPrompt prompt, String channelId, String threadTs, String senderId) {
        if (prompt == null || prompt.getReplyTarget() == null || prompt.getChannelIdentity() == null) {
            return false;
        }
        if (!sameValue(prompt.getReplyTarget().getChatId(), channelId)) {
            return false;
        }
        String expectedThreadId = prompt.getReplyTarget().getThreadId();
        if (!isBlank(expectedThreadId) && !sameValue(expectedThreadId, threadTs)) {
            return false;
        }
        String expectedSenderId = prompt.getChannelIdentity().getSenderId();
        return isBlank(expectedSenderId) || sameValue(expectedSenderId, senderId);
    }

    private boolean matchesSender(ChannelIdentity identity, String senderId) {
        return identity == null || isBlank(identity.getSenderId()) || sameValue(identity.getSenderId(), senderId);
    }

    private String clarifyAnswer(ClarifyPrompt prompt, String optionIndex) {
        List<String> options = prompt.getOptions();
        if (options == null || options.isEmpty()) {
            return "";
        }
        try {
            int index = Integer.parseInt(optionIndex);
            return index >= 0 && index < options.size() ? options.get(index) : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private String approvalStatus(String decision) {
        return switch (decision) {
            case "approve" -> "approved";
            case "always" -> "always allowed";
            default -> "rejected";
        };
    }

    private String shortToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean sameValue(String expected, String actual) {
        return expected != null && actual != null && expected.equals(actual);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PendingApproval(ApprovalPrompt prompt,
                                   CompletableFuture<ApprovalDecision> future,
                                   String token,
                                   String channelId,
                                   String messageTs) {
    }

    private record PendingClarify(ClarifyPrompt prompt,
                                  CompletableFuture<ClarifyDecision> future,
                                  String token,
                                  String channelId,
                                  String messageTs) {
    }
}
