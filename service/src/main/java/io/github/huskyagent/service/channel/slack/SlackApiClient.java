package io.github.huskyagent.service.channel.slack;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.auth.AuthTestResponse;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.chat.ChatUpdateResponse;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.element.BlockElement;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import io.github.huskyagent.infra.channel.ReplyTarget;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asElements;
import static com.slack.api.model.block.element.BlockElements.button;

@Slf4j
public class SlackApiClient {

    static final int MAX_MESSAGE_CHARS = 3900;

    private final SlackProperties.InstanceProperties properties;
    private final Slack slack;
    private final MethodsClient methods;

    public SlackApiClient(SlackProperties.InstanceProperties properties) {
        this(properties, Slack.getInstance());
    }

    SlackApiClient(SlackProperties.InstanceProperties properties, Slack slack) {
        this.properties = properties;
        this.slack = slack;
        this.methods = isBlank(properties.getBotToken()) ? null : slack.methods(properties.getBotToken());
    }

    public MethodsClient methods() {
        return methods;
    }

    public String resolveBotUserId() {
        if (!isBlank(properties.getBotUserId())) {
            return normalizeBotUserId(properties.getBotUserId());
        }
        if (methods == null) {
            return "";
        }
        try {
            AuthTestResponse response = methods.authTest(r -> r);
            if (response == null || !response.isOk() || isBlank(response.getUserId())) {
                throw new IllegalStateException("Failed to resolve Slack bot user id");
            }
            properties.setBotUserId(normalizeBotUserId(response.getUserId()));
            if (isBlank(properties.getTeamId())) {
                properties.setTeamId(response.getTeamId());
            }
            return properties.getBotUserId();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve Slack bot user id", e);
        }
    }

    public SlackSentMessage sendText(SlackSendTarget target, String text) {
        if (methods == null || target == null || isBlank(target.getChannelId()) || isBlank(text)) {
            return null;
        }
        SlackSentMessage last = null;
        for (String part : split(text)) {
            try {
                ChatPostMessageResponse response = methods.chatPostMessage(req -> req
                        .channel(target.getChannelId())
                        .threadTs(target.getThreadTs())
                        .replyBroadcast(target.isReplyBroadcast())
                        .text(part));
                if (response != null && response.isOk()) {
                    last = new SlackSentMessage(response.getChannel(), response.getTs());
                } else {
                    log.warn("Slack chat.postMessage failed: error={}", response != null ? response.getError() : null);
                }
            } catch (Exception e) {
                log.warn("Slack chat.postMessage failed", e);
            }
        }
        return last;
    }

    public SlackSentMessage sendApprovalMessage(SlackSendTarget target, ApprovalPrompt prompt,
                                                String approveValue, String alwaysValue, String rejectValue) {
        String text = "Approval required\n"
                + "Tool: " + safe(prompt.getToolName()) + "\n"
                + "Reason: " + safe(prompt.getReason()) + "\n"
                + "Arguments: " + truncate(safe(prompt.getToolArgs()), 1200);
        return sendInteractiveMessage(target, text, asBlocks(
                section(s -> s.text(markdownText(text))),
                actions(a -> a.elements(asElements(
                        button(b -> b.text(plainText("Approve")).actionId("husky_approval").value(approveValue)),
                        button(b -> b.text(plainText("Always allow")).actionId("husky_approval").value(alwaysValue)),
                        button(b -> b.text(plainText("Reject")).actionId("husky_approval").value(rejectValue))
                )))
        ));
    }

    public void editApprovalMessage(String channelId, String ts, ApprovalPrompt prompt, String status) {
        String text = "Approval " + status + "\n"
                + "Tool: " + safe(prompt.getToolName()) + "\n"
                + "Reason: " + safe(prompt.getReason());
        updateMessage(channelId, ts, text, asBlocks(section(s -> s.text(markdownText(text)))));
    }

    public SlackSentMessage sendClarifyMessage(SlackSendTarget target, ClarifyPrompt prompt, List<String> callbackData) {
        String text = "Clarification required\n" + safe(prompt.getQuestion());
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(section(s -> s.text(markdownText(text))));
        List<String> options = prompt.getOptions();
        if (options != null && !options.isEmpty()) {
            List<BlockElement> elements = new ArrayList<>();
            for (int i = 0; i < options.size(); i++) {
                int index = i;
                elements.add(button(b -> b
                        .text(plainText(truncate(options.get(index), 70)))
                        .actionId("husky_clarify")
                        .value(callbackData.get(index))));
            }
            blocks.add(actions(a -> a.elements(elements)));
        }
        return sendInteractiveMessage(target, text, blocks);
    }

    public void editClarifyMessage(String channelId, String ts, ClarifyPrompt prompt, String status, String answer) {
        String text = "Clarification " + status + "\n"
                + safe(prompt.getQuestion())
                + (isBlank(answer) ? "" : "\nAnswer: " + safe(answer));
        updateMessage(channelId, ts, text, asBlocks(section(s -> s.text(markdownText(text)))));
    }

    private SlackSentMessage sendInteractiveMessage(SlackSendTarget target, String text, List<LayoutBlock> blocks) {
        if (methods == null || target == null || isBlank(target.getChannelId())) {
            return null;
        }
        try {
            ChatPostMessageResponse response = methods.chatPostMessage(req -> req
                    .channel(target.getChannelId())
                    .threadTs(target.getThreadTs())
                    .replyBroadcast(target.isReplyBroadcast())
                    .text(truncate(text, MAX_MESSAGE_CHARS))
                    .blocks(blocks));
            if (response == null || !response.isOk()) {
                log.warn("Slack interactive message failed: error={}", response != null ? response.getError() : null);
                return null;
            }
            return new SlackSentMessage(response.getChannel(), response.getTs());
        } catch (Exception e) {
            log.warn("Slack interactive message failed", e);
            return null;
        }
    }

    private void updateMessage(String channelId, String ts, String text, List<LayoutBlock> blocks) {
        if (methods == null || isBlank(channelId) || isBlank(ts)) {
            return;
        }
        try {
            ChatUpdateResponse response = methods.chatUpdate(req -> req
                    .channel(channelId)
                    .ts(ts)
                    .text(truncate(text, MAX_MESSAGE_CHARS))
                    .blocks(blocks));
            if (response == null || !response.isOk()) {
                log.warn("Slack chat.update failed: error={}", response != null ? response.getError() : null);
            }
        } catch (Exception e) {
            log.warn("Slack chat.update failed", e);
        }
    }

    static SlackSendTarget target(ReplyTarget replyTarget, boolean replyBroadcast) {
        if (replyTarget == null) {
            return SlackSendTarget.builder().replyBroadcast(replyBroadcast).build();
        }
        return SlackSendTarget.builder()
                .channelId(replyTarget.getChatId())
                .threadTs(!isBlank(replyTarget.getThreadId()) ? replyTarget.getThreadId() : replyTarget.getMessageId())
                .replyBroadcast(replyBroadcast)
                .build();
    }

    static String normalizeBotUserId(String value) {
        return value == null ? "" : value.trim();
    }

    private List<String> split(String text) {
        if (text.length() <= MAX_MESSAGE_CHARS) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(index + MAX_MESSAGE_CHARS, text.length());
            parts.add(text.substring(index, end));
            index = end;
        }
        return parts;
    }

    static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Value
    @Builder
    public static class SlackSendTarget {
        String channelId;
        String threadTs;
        boolean replyBroadcast;
    }

    public record SlackSentMessage(String channelId, String ts) {
    }
}
