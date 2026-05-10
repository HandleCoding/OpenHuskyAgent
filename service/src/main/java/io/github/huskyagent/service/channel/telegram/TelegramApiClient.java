package io.github.huskyagent.service.channel.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.LinkPreviewOptions;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.model.request.ChatAction;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.model.request.ReplyParameters;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.GetMe;
import com.pengrad.telegrambot.request.SendChatAction;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetMeResponse;
import com.pengrad.telegrambot.response.SendResponse;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import io.github.huskyagent.infra.channel.ReplyTarget;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class TelegramApiClient {

    static final int MAX_MESSAGE_CHARS = 3900;

    private final TelegramProperties.InstanceProperties properties;
    private final TelegramBot bot;

    public TelegramApiClient(TelegramProperties.InstanceProperties properties) {
        this(properties, isBlank(properties.getToken()) ? null : new TelegramBot(properties.getToken()));
    }

    TelegramApiClient(TelegramProperties.InstanceProperties properties, TelegramBot bot) {
        this.properties = properties;
        this.bot = bot;
    }

    public TelegramBot bot() {
        return bot;
    }

    public String resolveBotUsername() {
        if (!isBlank(properties.getBotUsername())) {
            return normalizeUsername(properties.getBotUsername());
        }
        if (bot == null) {
            return "";
        }
        GetMeResponse response = bot.execute(new GetMe());
        if (response == null || !response.isOk() || response.user() == null) {
            throw new IllegalStateException("Failed to resolve Telegram bot username");
        }
        User user = response.user();
        String username = normalizeUsername(user.username());
        properties.setBotUsername(username);
        return username;
    }

    public void sendText(TelegramSendTarget target, String text) {
        if (bot == null || target == null || isBlank(target.getChatId()) || isBlank(text)) {
            return;
        }
        Long chatId = parseLong(target.getChatId());
        if (chatId == null) {
            log.warn("Invalid Telegram chat id: {}", target.getChatId());
            return;
        }
        for (String part : split(text)) {
            SendMessage request = new SendMessage(chatId, part).linkPreviewOptions(new LinkPreviewOptions().isDisabled(true));
            Integer threadId = parseInteger(target.getThreadId());
            if (threadId != null) {
                request.messageThreadId(threadId);
            }
            Integer replyTo = parseInteger(target.getMessageId());
            if (replyTo != null) {
                request.replyParameters(new ReplyParameters(replyTo));
            }
            bot.execute(request);
        }
    }

    public void typing(TelegramSendTarget target) {
        if (bot == null || target == null || isBlank(target.getChatId())) {
            return;
        }
        Long chatId = parseLong(target.getChatId());
        if (chatId == null) {
            return;
        }
        SendChatAction request = new SendChatAction(chatId, ChatAction.typing);
        Integer threadId = parseInteger(target.getThreadId());
        if (threadId != null) {
            request.messageThreadId(threadId);
        }
        bot.execute(request);
    }

    public Integer sendApprovalMessage(TelegramSendTarget target, ApprovalPrompt prompt, String approveData, String alwaysData, String rejectData) {
        String text = "Approval required\n"
                + "Tool: " + safe(prompt.getToolName()) + "\n"
                + "Reason: " + safe(prompt.getReason()) + "\n"
                + "Arguments: " + truncate(safe(prompt.getToolArgs()), 1200);
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup(new InlineKeyboardButton[]{
                new InlineKeyboardButton("Approve").callbackData(approveData),
                new InlineKeyboardButton("Always allow").callbackData(alwaysData),
                new InlineKeyboardButton("Reject").callbackData(rejectData)
        });
        return sendInteractiveMessage(target, text, keyboard);
    }

    public void editApprovalMessage(String chatId, Integer messageId, ApprovalPrompt prompt, String status) {
        String text = "Approval " + status + "\n"
                + "Tool: " + safe(prompt.getToolName()) + "\n"
                + "Reason: " + safe(prompt.getReason());
        editMessage(chatId, messageId, text);
    }

    public Integer sendClarifyMessage(TelegramSendTarget target, ClarifyPrompt prompt, List<String> callbackData) {
        String text = "Clarification required\n" + safe(prompt.getQuestion());
        InlineKeyboardMarkup keyboard = null;
        List<String> options = prompt.getOptions();
        if (options != null && !options.isEmpty()) {
            InlineKeyboardButton[][] rows = new InlineKeyboardButton[options.size()][];
            for (int i = 0; i < options.size(); i++) {
                rows[i] = new InlineKeyboardButton[]{new InlineKeyboardButton(options.get(i)).callbackData(callbackData.get(i))};
            }
            keyboard = new InlineKeyboardMarkup(rows);
        }
        return sendInteractiveMessage(target, text, keyboard);
    }

    public void editClarifyMessage(String chatId, Integer messageId, ClarifyPrompt prompt, String status, String answer) {
        String text = "Clarification " + status + "\n"
                + safe(prompt.getQuestion())
                + (isBlank(answer) ? "" : "\nAnswer: " + safe(answer));
        editMessage(chatId, messageId, text);
    }

    public void answerCallback(String callbackQueryId, String text) {
        if (bot == null || isBlank(callbackQueryId)) {
            return;
        }
        bot.execute(new AnswerCallbackQuery(callbackQueryId).text(text != null ? text : ""));
    }

    private Integer sendInteractiveMessage(TelegramSendTarget target, String text, InlineKeyboardMarkup keyboard) {
        if (bot == null || target == null || isBlank(target.getChatId())) {
            return null;
        }
        Long chatId = parseLong(target.getChatId());
        if (chatId == null) {
            return null;
        }
        SendMessage request = new SendMessage(chatId, truncate(text, MAX_MESSAGE_CHARS)).linkPreviewOptions(new LinkPreviewOptions().isDisabled(true));
        Integer threadId = parseInteger(target.getThreadId());
        if (threadId != null) {
            request.messageThreadId(threadId);
        }
        Integer replyTo = parseInteger(target.getMessageId());
        if (replyTo != null) {
            request.replyParameters(new ReplyParameters(replyTo));
        }
        if (keyboard != null) {
            request.replyMarkup(keyboard);
        }
        SendResponse response = bot.execute(request);
        Message message = response != null ? response.message() : null;
        return message != null ? message.messageId() : null;
    }

    private void editMessage(String chatIdValue, Integer messageId, String text) {
        if (bot == null || isBlank(chatIdValue) || messageId == null) {
            return;
        }
        Long chatId = parseLong(chatIdValue);
        if (chatId == null) {
            return;
        }
        bot.execute(new EditMessageText(chatId, messageId, truncate(text, MAX_MESSAGE_CHARS)).linkPreviewOptions(new LinkPreviewOptions().isDisabled(true)));
    }

    static TelegramSendTarget target(ReplyTarget replyTarget) {
        if (replyTarget == null) {
            return TelegramSendTarget.builder().build();
        }
        return TelegramSendTarget.builder()
                .chatId(replyTarget.getChatId())
                .threadId(replyTarget.getThreadId())
                .messageId(replyTarget.getMessageId())
                .build();
    }

    static String normalizeUsername(String username) {
        if (username == null) {
            return "";
        }
        String value = username.trim();
        return value.startsWith("@") ? value.substring(1) : value;
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

    private static Long parseLong(String value) {
        try {
            return isBlank(value) ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInteger(String value) {
        try {
            return isBlank(value) ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars - 3) + "...";
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Value
    @Builder
    public static class TelegramSendTarget {
        String chatId;
        String threadId;
        String messageId;
    }
}
