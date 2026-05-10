package io.github.huskyagent.service.channel.telegram;

import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
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
public class TelegramInstanceAdapter extends AbstractChannelAdapter {

    private final TelegramProperties.InstanceProperties properties;
    private final TelegramApiClient apiClient;
    private final ToolDisplayMessageRenderer toolDisplayMessageRenderer;
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, String> approvalRequestByToken = new ConcurrentHashMap<>();
    private final Map<String, PendingClarify> pendingClarifications = new ConcurrentHashMap<>();
    private final Map<String, String> clarifyRequestByToken = new ConcurrentHashMap<>();
    private String platformAccountId;

    public TelegramInstanceAdapter(TelegramProperties.InstanceProperties properties,
                                   TelegramApiClient apiClient,
                                   ToolDisplayMessageRenderer toolDisplayMessageRenderer) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.toolDisplayMessageRenderer = toolDisplayMessageRenderer;
        this.platformAccountId = TelegramApiClient.normalizeUsername(properties.getBotUsername());
    }

    public String platformAccountId() {
        if (isBlank(platformAccountId)) {
            platformAccountId = TelegramApiClient.normalizeUsername(apiClient.resolveBotUsername());
        }
        return platformAccountId;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.builder()
                .supportsApprovalCard(true)
                .supportsEdit(true)
                .supportsTyping(true)
                .supportsStreaming(false)
                .supportsImageInput(false)
                .requiresMentionInGroup(properties.isMentionRequiredInGroup())
                .build();
    }

    @Override
    public InboundMessage normalizeInbound(Object rawEvent, ChannelAuthContext authContext) {
        if (!(rawEvent instanceof Update update)) {
            return InboundMessage.ignored(rawEvent);
        }
        if (update.callbackQuery() != null) {
            return InboundMessage.ignored(rawEvent);
        }
        Message message = update.message();
        if (message == null || message.text() == null || message.text().isBlank()) {
            return InboundMessage.ignored(rawEvent);
        }
        Chat chat = message.chat();
        User from = message.from();
        if (chat == null || from == null) {
            return InboundMessage.ignored(rawEvent);
        }
        String chatId = String.valueOf(chat.id());
        String senderId = String.valueOf(from.id());
        String threadId = message.messageThreadId() != null ? String.valueOf(message.messageThreadId()) : null;
        ConversationType conversationType = conversationType(chat, threadId);
        boolean groupLike = conversationType == ConversationType.GROUP || conversationType == ConversationType.THREAD;
        String text = message.text().trim();

        if (groupLike && properties.isMentionRequiredInGroup() && !addressesBot(text, message.replyToMessage())) {
            return InboundMessage.ignored(rawEvent);
        }
        text = stripBotAddress(text).trim();
        if (completePendingOpenClarify(chatId, threadId, senderId, text)) {
            return InboundMessage.ignored(rawEvent);
        }
        if (text.isBlank()) {
            return InboundMessage.ignored(rawEvent);
        }

        return buildInbound(update, authContext, message, chat, from, text, threadId, conversationType);
    }

    @Override
    public void send(OutboundMessage message) {
        if (message == null || message.getKind() == OutboundMessage.Kind.TOKEN || message.getKind() == OutboundMessage.Kind.REASONING) {
            return;
        }
        if (message.getText() == null || message.getText().isBlank()) {
            return;
        }
        apiClient.sendText(TelegramApiClient.target(message.getReplyTarget()), message.getText());
    }

    @Override
    public void typing(OutboundMessage message) {
        if (message == null || message.getMetadata() == null) {
            return;
        }
        String status = String.valueOf(message.getMetadata().getOrDefault("status", ""));
        if ("started".equals(status)) {
            apiClient.typing(TelegramApiClient.target(message.getReplyTarget()));
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
        pendingApprovals.put(prompt.getRequestId(), new PendingApproval(prompt, future, token, null));
        Integer messageId = apiClient.sendApprovalMessage(
                TelegramApiClient.target(prompt.getReplyTarget()),
                prompt,
                "husky:a:" + token + ":approve",
                "husky:a:" + token + ":always",
                "husky:a:" + token + ":reject");
        if (messageId == null) {
            pendingApprovals.remove(prompt.getRequestId());
            approvalRequestByToken.remove(token);
            return ApprovalDecision.deny("Failed to send Telegram approval message");
        }
        pendingApprovals.computeIfPresent(prompt.getRequestId(), (key, pending) ->
                pending.future() == future ? new PendingApproval(prompt, future, token, messageId) : pending);
        try {
            return future.get(properties.getApprovalTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            PendingApproval pending = pendingApprovals.remove(prompt.getRequestId());
            approvalRequestByToken.remove(token);
            if (pending != null) {
                apiClient.editApprovalMessage(chatId(prompt.getReplyTarget()), pending.messageId(), prompt, "timed out");
            }
            return ApprovalDecision.deny("Approval timed out");
        }
    }

    public boolean completeApproval(String token, String decision, String callbackQueryId, String senderId) {
        String requestId = approvalRequestByToken.remove(token);
        PendingApproval pending = requestId != null ? pendingApprovals.remove(requestId) : null;
        if (pending == null) {
            apiClient.answerCallback(callbackQueryId, "Approval request is no longer pending");
            return false;
        }
        if (!matchesSender(pending.prompt().getChannelIdentity(), senderId)) {
            pendingApprovals.put(requestId, pending);
            approvalRequestByToken.put(token, requestId);
            apiClient.answerCallback(callbackQueryId, "Only the original requester can answer this approval");
            return false;
        }
        ApprovalDecision approvalDecision = switch (decision) {
            case "approve" -> ApprovalDecision.builder().approved(true).always(false).reason("Approved from Telegram").build();
            case "always" -> ApprovalDecision.builder().approved(true).always(true).reason("Always allowed from Telegram").build();
            default -> ApprovalDecision.deny("Rejected from Telegram");
        };
        pending.future().complete(approvalDecision);
        apiClient.answerCallback(callbackQueryId, approvalDecision.isApproved() ? "Approved" : "Rejected");
        apiClient.editApprovalMessage(chatId(pending.prompt().getReplyTarget()), pending.messageId(), pending.prompt(), approvalStatus(decision));
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
        pendingClarifications.put(prompt.getRequestId(), new PendingClarify(prompt, future, token, null));
        List<String> callbacks = new ArrayList<>();
        if (prompt.getOptions() != null) {
            for (int i = 0; i < prompt.getOptions().size(); i++) {
                callbacks.add("husky:c:" + token + ":" + i);
            }
        }
        Integer messageId = apiClient.sendClarifyMessage(TelegramApiClient.target(prompt.getReplyTarget()), prompt, callbacks);
        if (messageId == null) {
            pendingClarifications.remove(prompt.getRequestId());
            clarifyRequestByToken.remove(token);
            return ClarifyDecision.answer("");
        }
        pendingClarifications.computeIfPresent(prompt.getRequestId(), (key, pending) ->
                pending.future() == future ? new PendingClarify(prompt, future, token, messageId) : pending);
        try {
            return future.get(properties.getApprovalTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            PendingClarify pending = pendingClarifications.remove(prompt.getRequestId());
            clarifyRequestByToken.remove(token);
            if (pending != null) {
                apiClient.editClarifyMessage(chatId(prompt.getReplyTarget()), pending.messageId(), prompt, "timed out", "");
            }
            return ClarifyDecision.answer("");
        }
    }

    public boolean completeClarify(String token, String optionIndex, String callbackQueryId, String senderId) {
        String requestId = clarifyRequestByToken.remove(token);
        PendingClarify pending = requestId != null ? pendingClarifications.remove(requestId) : null;
        if (pending == null) {
            apiClient.answerCallback(callbackQueryId, "Clarification request is no longer pending");
            return false;
        }
        if (!matchesSender(pending.prompt().getChannelIdentity(), senderId)) {
            pendingClarifications.put(requestId, pending);
            clarifyRequestByToken.put(token, requestId);
            apiClient.answerCallback(callbackQueryId, "Only the original requester can answer this clarification");
            return false;
        }
        String answer = clarifyAnswer(pending.prompt(), optionIndex);
        ClarifyDecision decision = ClarifyDecision.answer(answer);
        pending.future().complete(decision);
        apiClient.answerCallback(callbackQueryId, "Answer submitted");
        apiClient.editClarifyMessage(chatId(pending.prompt().getReplyTarget()), pending.messageId(), pending.prompt(), "answered", decision.getAnswer());
        return true;
    }

    boolean completePendingOpenClarify(String chatId, String threadId, String senderId, String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        for (Map.Entry<String, PendingClarify> entry : pendingClarifications.entrySet()) {
            ClarifyPrompt prompt = entry.getValue().prompt();
            if (prompt.getOptions() != null && !prompt.getOptions().isEmpty()) {
                continue;
            }
            if (matchesClarifyReply(prompt, chatId, threadId, senderId)) {
                String token = entry.getValue().token();
                pendingClarifications.remove(entry.getKey());
                clarifyRequestByToken.remove(token);
                entry.getValue().future().complete(ClarifyDecision.answer(answer));
                apiClient.editClarifyMessage(chatId(prompt.getReplyTarget()), entry.getValue().messageId(), prompt, "answered", answer);
                return true;
            }
        }
        return false;
    }

    public boolean handleCallback(CallbackQuery callbackQuery) {
        if (callbackQuery == null || callbackQuery.data() == null) {
            return false;
        }
        String[] parts = callbackQuery.data().split(":", 4);
        String callbackId = callbackQuery.id();
        String senderId = callbackQuery.from() != null ? String.valueOf(callbackQuery.from().id()) : null;
        if (parts.length == 4 && "husky".equals(parts[0]) && "a".equals(parts[1])) {
            return completeApproval(parts[2], parts[3], callbackId, senderId);
        }
        if (parts.length == 4 && "husky".equals(parts[0]) && "c".equals(parts[1])) {
            return completeClarify(parts[2], parts[3], callbackId, senderId);
        }
        return false;
    }

    private InboundMessage buildInbound(Update update,
                                        ChannelAuthContext authContext,
                                        Message message,
                                        Chat chat,
                                        User from,
                                        String text,
                                        String threadId,
                                        ConversationType conversationType) {
        String chatId = String.valueOf(chat.id());
        String senderId = String.valueOf(from.id());
        ChannelIdentity channelIdentity = ChannelIdentity.builder()
                .channelType(ChannelType.TELEGRAM)
                .conversationType(conversationType)
                .platformAccountId(platformAccountId())
                .chatId(chatId)
                .threadId(threadId)
                .senderId(senderId)
                .connectionId(authContext != null ? authContext.getConnectionId() : null)
                .build();
        ReplyTarget replyTarget = ReplyTarget.builder()
                .chatId(chatId)
                .threadId(threadId)
                .messageId(String.valueOf(message.messageId()))
                .build();
        return InboundMessage.builder()
                .messageId(update.updateId() + ":" + message.messageId())
                .text(text)
                .contentParts(List.of(InboundContentPart.text(text)))
                .principal(Principal.builder()
                        .id(principalId(senderId, chatId, threadId, conversationType))
                        .displayName(displayName(from))
                        .channelType(ChannelType.TELEGRAM)
                        .build())
                .channelIdentity(channelIdentity)
                .sceneId(properties.getDefaultScene())
                .replyTarget(replyTarget)
                .rawPayload(update)
                .metadata(Map.of("telegramChatType", chat.type().name()))
                .build();
    }

    private ConversationType conversationType(Chat chat, String threadId) {
        String type = chat.type() != null ? chat.type().name() : "";
        if ("Private".equalsIgnoreCase(type)) {
            return ConversationType.DIRECT;
        }
        if (!isBlank(threadId)) {
            return ConversationType.THREAD;
        }
        return ConversationType.GROUP;
    }

    private boolean addressesBot(String text, Message replyToMessage) {
        String username = platformAccountId();
        if (!isBlank(username) && (botMentionPattern(username).matcher(text).find() || botCommandPattern(username).matcher(text).find())) {
            return true;
        }
        User replyUser = replyToMessage != null ? replyToMessage.from() : null;
        return replyUser != null && Boolean.TRUE.equals(replyUser.isBot())
                && !isBlank(username) && username.equalsIgnoreCase(replyUser.username());
    }

    private String stripBotAddress(String text) {
        String username = platformAccountId();
        if (isBlank(username) || text == null) {
            return text != null ? text : "";
        }
        return botMentionPattern(username).matcher(botCommandPattern(username).matcher(text).replaceAll("$1")).replaceAll("");
    }

    private Pattern botMentionPattern(String username) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9_])@" + Pattern.quote(username) + "(?![A-Za-z0-9_])");
    }

    private Pattern botCommandPattern(String username) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9_])(/\\w+)@" + Pattern.quote(username) + "(?![A-Za-z0-9_])");
    }

    private String principalId(String senderId, String chatId, String threadId, ConversationType conversationType) {
        String prefix = "telegram:" + platformAccountId() + ":";
        if (conversationType == ConversationType.DIRECT || properties.getGroupSessionScope() == TelegramProperties.GroupSessionScope.USER) {
            return prefix + senderId;
        }
        if (properties.getGroupSessionScope() == TelegramProperties.GroupSessionScope.CHAT) {
            return prefix + "chat:" + chatId;
        }
        return prefix + "chat:" + chatId + ":thread:" + (!isBlank(threadId) ? threadId : "main");
    }

    private boolean matchesClarifyReply(ClarifyPrompt prompt, String chatId, String threadId, String senderId) {
        if (prompt == null || prompt.getReplyTarget() == null || prompt.getChannelIdentity() == null) {
            return false;
        }
        if (!sameValue(prompt.getReplyTarget().getChatId(), chatId)) {
            return false;
        }
        String expectedThreadId = prompt.getReplyTarget().getThreadId();
        if (!isBlank(expectedThreadId) && !sameValue(expectedThreadId, threadId)) {
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

    private String displayName(User user) {
        String fullName = ((user.firstName() != null ? user.firstName() : "") + " " + (user.lastName() != null ? user.lastName() : "")).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        return !isBlank(user.username()) ? user.username() : String.valueOf(user.id());
    }

    private String chatId(ReplyTarget replyTarget) {
        return replyTarget != null ? replyTarget.getChatId() : null;
    }

    private String shortToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
                                   Integer messageId) {
    }

    private record PendingClarify(ClarifyPrompt prompt,
                                  CompletableFuture<ClarifyDecision> future,
                                  String token,
                                  Integer messageId) {
    }
}
