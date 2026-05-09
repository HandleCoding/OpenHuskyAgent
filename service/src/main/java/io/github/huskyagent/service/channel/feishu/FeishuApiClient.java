package io.github.huskyagent.service.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.service.contact.v3.model.GetUserReq;
import com.lark.oapi.service.contact.v3.model.GetUserResp;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionResp;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.DeleteMessageReactionReq;
import com.lark.oapi.service.im.v1.model.DeleteMessageReactionResp;
import com.lark.oapi.service.im.v1.model.Emoji;
import com.lark.oapi.service.im.v1.model.PatchMessageReq;
import com.lark.oapi.service.im.v1.model.PatchMessageReqBody;
import com.lark.oapi.service.im.v1.model.PatchMessageResp;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
public class FeishuApiClient {

    private final FeishuProperties.InstanceProperties properties;
    private final ObjectMapper objectMapper;
    private final FeishuCardRenderer cardRenderer;

    private volatile Client client;
    private final ConcurrentHashMap<String, String> userDisplayNameCache = new ConcurrentHashMap<>();

    public record DownloadedResource(byte[] data, String mimeType, String filename, long sizeBytes) {
    }

    public void initBotOpenId() {
        if (properties.getBotOpenId() != null && !properties.getBotOpenId().isBlank()) {
            log.info("Feishu bot open_id already configured: {}", properties.getBotOpenId());
            return;
        }
        try {
            String token = fetchTenantAccessToken();
            if (token == null) {
                log.warn("Feishu initBotOpenId: failed to get tenant_access_token");
                return;
            }
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.feishu.cn/open-apis/bot/v3/info"))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
            if (root.path("code").asInt(-1) == 0) {
                String openId = root.path("bot").path("open_id").asText(null);
                if (openId != null && !openId.isBlank()) {
                    properties.setBotOpenId(openId);
                    log.info("Feishu bot open_id resolved automatically: {}", openId);
                    return;
                }
            }
            log.warn("Feishu initBotOpenId: unexpected response: {}", resp.body());
        } catch (Exception e) {
            log.warn("Feishu initBotOpenId error: {}", e.getMessage());
        }
    }

    private String fetchTenantAccessToken() {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "app_id", properties.getAppId(),
                    "app_secret", properties.getAppSecret()
            ));
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
            if (root.path("code").asInt(-1) == 0) {
                return root.path("tenant_access_token").asText(null);
            }
            log.warn("Feishu fetchTenantAccessToken failed: {}", resp.body());
        } catch (Exception e) {
            log.warn("Feishu fetchTenantAccessToken error: {}", e.getMessage());
        }
        return null;
    }

    public String getUserDisplayName(String openId) {
        if (openId == null || openId.isBlank()) {
            return null;
        }
        return userDisplayNameCache.computeIfAbsent(openId, id -> {
            try {
                GetUserResp resp = client().contact().v3().user().get(
                        GetUserReq.newBuilder().userId(id).userIdType("open_id").build()
                );
                if (resp.success() && resp.getData() != null && resp.getData().getUser() != null) {
                    String name = resp.getData().getUser().getName();
                    return (name != null && !name.isBlank()) ? name : null;
                }
                log.debug("Feishu getUserDisplayName failed for openId={}: code={}, msg={}",
                        id, resp.getCode(), resp.getMsg());
            } catch (Exception e) {
                log.debug("Feishu getUserDisplayName error for openId={}: {}", id, e.getMessage());
            }
            return null;
        });
    }

    public DownloadedResource downloadImage(String messageId, String imageKey) {
        if (!properties.isEnabled()) {
            log.info("Feishu disabled; skip downloading image messageId={}", messageId);
            return null;
        }
        if (messageId == null || messageId.isBlank() || imageKey == null || imageKey.isBlank()) {
            return null;
        }
        try {
            GetMessageResourceReq request = GetMessageResourceReq.newBuilder()
                    .messageId(messageId)
                    .fileKey(imageKey)
                    .type("image")
                    .build();
            GetMessageResourceResp response = client().im().v1().messageResource().get(request);
            if (!response.success()) {
                log.warn("Feishu image download failed: messageId={}, imageKey={}, code={}, msg={}",
                        messageId, imageKey, response.getCode(), response.getMsg());
                return null;
            }
            byte[] data = response.getData() != null
                    ? response.getData().toByteArray()
                    : response.getRawResponse() != null ? response.getRawResponse().getBody() : null;
            if (data == null || data.length == 0) {
                return null;
            }
            if (data.length > properties.getMaxInboundImageBytes()) {
                log.warn("Feishu image exceeds size limit: messageId={}, imageKey={}, size={}, limit={}",
                        messageId, imageKey, data.length, properties.getMaxInboundImageBytes());
                return null;
            }
            String mimeType = normalizeImageMimeType(response.getRawResponse() != null
                    ? response.getRawResponse().getContentType()
                    : null, response.getFileName(), data);
            if (mimeType == null) {
                log.warn("Feishu image has unsupported mime type, defaulting to image/jpeg: messageId={}, imageKey={}, filename={}",
                        messageId, imageKey, response.getFileName());
                mimeType = "image/jpeg";
            }
            String filename = firstNonBlank(response.getFileName(), imageKey + extensionFor(mimeType));
            log.info("Feishu image downloaded: messageId={}, imageKey={}, mimeType={}, filename={}, sizeBytes={}",
                    messageId, imageKey, mimeType, filename, data.length);
            return new DownloadedResource(data, mimeType, filename, data.length);
        } catch (Exception e) {
            log.warn("Failed to download Feishu image: messageId={}, imageKey={}", messageId, imageKey, e);
            return null;
        }
    }

    public void sendText(FeishuSendTarget message) {
        if (!properties.isEnabled()) {
            log.info("Feishu disabled; skip sending message to chatId={}", message.getChatId());
            return;
        }
        if (message.getChatId() == null || message.getChatId().isBlank()) {
            log.warn("Cannot send Feishu message without chatId");
            return;
        }
        try {
            boolean isReply = message.getMessageId() != null && !message.getMessageId().isBlank();
            String raw = message.getText() != null ? message.getText() : "";
            java.util.List<String> chunks = cardRenderer.splitByTableLimit(raw, 4);
            for (String chunk : chunks) {
                String cardContent = cardRenderer.markdownCardContent(chunk);
                boolean ok = isReply ? reply(message, "interactive", cardContent)
                        : create(message, "interactive", cardContent);
                if (!ok) {
                    String textContent = objectMapper.writeValueAsString(Map.of("text", cardRenderer.stripMarkdown(chunk)));
                    if (isReply) {
                        reply(message, "text", textContent);
                    } else {
                        create(message, "text", textContent);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to send Feishu text message", e);
        }
    }

    public String sendApprovalCard(FeishuSendTarget target, ApprovalPrompt prompt) {
        if (!properties.isEnabled()) {
            return null;
        }
        try {
            String content = cardRenderer.approvalCardContent(prompt, "pending");
            String messageId = target.getMessageId() != null && !target.getMessageId().isBlank()
                    ? replyForMessageId(target, "interactive", content)
                    : createForMessageId(target, "interactive", content);
            log.info("Feishu approval card sent: requestId={}, cardMessageId={}", prompt.getRequestId(), messageId);
            return messageId;
        } catch (Exception e) {
            log.error("Failed to send Feishu approval card: requestId={}", prompt.getRequestId(), e);
            return null;
        }
    }

    public void updateApprovalCard(String messageId, ApprovalPrompt prompt, String status) {
        if (!properties.isEnabled() || messageId == null || messageId.isBlank()) {
            return;
        }
        try {
            PatchMessageReq request = PatchMessageReq.newBuilder()
                    .messageId(messageId)
                    .patchMessageReqBody(PatchMessageReqBody.newBuilder()
                            .content(cardRenderer.approvalCardContent(prompt, status))
                            .build())
                    .build();
            PatchMessageResp response = client().im().v1().message().patch(request);
            if (!response.success()) {
                log.debug("Feishu update approval card failed, messageId={}, code={}, msg={}",
                        messageId, response.getCode(), response.getMsg());
            }
        } catch (Exception e) {
            log.debug("Failed to update Feishu approval card: messageId={}", messageId, e);
        }
    }

    public String sendClarifyCard(FeishuSendTarget target, ClarifyPrompt prompt) {
        if (!properties.isEnabled()) {
            return null;
        }
        try {
            String content = cardRenderer.clarifyCardContent(prompt, "pending", "");
            String messageId = target.getMessageId() != null && !target.getMessageId().isBlank()
                    ? replyForMessageId(target, "interactive", content)
                    : createForMessageId(target, "interactive", content);
            log.info("Feishu clarify card sent: requestId={}, cardMessageId={}", prompt.getRequestId(), messageId);
            return messageId;
        } catch (Exception e) {
            log.error("Failed to send Feishu clarify card: requestId={}", prompt.getRequestId(), e);
            return null;
        }
    }

    public void updateClarifyCard(String messageId, ClarifyPrompt prompt, String status, String answer) {
        if (!properties.isEnabled() || messageId == null || messageId.isBlank()) {
            return;
        }
        try {
            PatchMessageReq request = PatchMessageReq.newBuilder()
                    .messageId(messageId)
                    .patchMessageReqBody(PatchMessageReqBody.newBuilder()
                            .content(cardRenderer.clarifyCardContent(prompt, status, answer))
                            .build())
                    .build();
            PatchMessageResp response = client().im().v1().message().patch(request);
            if (!response.success()) {
                log.debug("Feishu update clarify card failed, messageId={}, code={}, msg={}",
                        messageId, response.getCode(), response.getMsg());
            }
        } catch (Exception e) {
            log.debug("Failed to update Feishu clarify card: messageId={}", messageId, e);
        }
    }

    public String addReaction(String messageId, String emojiType) {
        if (!properties.isEnabled() || messageId == null || messageId.isBlank()) {
            return null;
        }
        try {
            CreateMessageReactionReq request = CreateMessageReactionReq.newBuilder()
                    .messageId(messageId)
                    .createMessageReactionReqBody(CreateMessageReactionReqBody.newBuilder()
                            .reactionType(Emoji.newBuilder().emojiType(emojiType).build())
                            .build())
                    .build();
            CreateMessageReactionResp response = client().im().v1().messageReaction().create(request);
            if (response.success() && response.getData() != null) {
                return response.getData().getReactionId();
            }
            log.debug("Feishu add reaction failed, messageId={}, emojiType={}, code={}, msg={}",
                    messageId, emojiType, response.getCode(), response.getMsg());
        } catch (Exception e) {
            log.debug("Failed to add Feishu reaction: messageId={}, emojiType={}", messageId, emojiType, e);
        }
        return null;
    }

    public boolean removeReaction(String messageId, String reactionId) {
        if (!properties.isEnabled() || messageId == null || messageId.isBlank()
                || reactionId == null || reactionId.isBlank()) {
            return false;
        }
        try {
            DeleteMessageReactionReq request = DeleteMessageReactionReq.newBuilder()
                    .messageId(messageId)
                    .reactionId(reactionId)
                    .build();
            DeleteMessageReactionResp response = client().im().v1().messageReaction().delete(request);
            if (!response.success()) {
                log.debug("Feishu remove reaction failed, messageId={}, reactionId={}, code={}, msg={}",
                        messageId, reactionId, response.getCode(), response.getMsg());
            }
            return response.success();
        } catch (Exception e) {
            log.debug("Failed to remove Feishu reaction: messageId={}, reactionId={}", messageId, reactionId, e);
            return false;
        }
    }

    // ── Image helpers ────────────────────────────────────────────────────────

    private String normalizeImageMimeType(String contentType, String filename, byte[] data) {
        if (data != null && data.length >= 4) {
            if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
                return "image/png";
            }
            if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) {
                return "image/jpeg";
            }
            if (data.length >= 12
                    && data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46
                    && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50) {
                return "image/webp";
            }
        }
        String mimeType = contentType;
        if (mimeType != null && mimeType.contains(";")) {
            mimeType = mimeType.substring(0, mimeType.indexOf(';'));
        }
        if (mimeType != null) {
            mimeType = mimeType.trim().toLowerCase(Locale.ROOT);
            if (isSupportedImageMimeType(mimeType)) {
                return mimeType;
            }
        }
        String lowerName = filename != null ? filename.toLowerCase(Locale.ROOT) : "";
        if (lowerName.endsWith(".png")) {
            return "image/png";
        }
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerName.endsWith(".webp")) {
            return "image/webp";
        }
        return null;
    }

    private boolean isSupportedImageMimeType(String mimeType) {
        return "image/png".equals(mimeType)
                || "image/jpeg".equals(mimeType)
                || "image/webp".equals(mimeType);
    }

    private String extensionFor(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    // ── Lark SDK message send ────────────────────────────────────────────────

    private String replyForMessageId(FeishuSendTarget message, String msgType, String content) throws Exception {
        ReplyMessageReq request = ReplyMessageReq.newBuilder()
                .messageId(message.getMessageId())
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                        .msgType(msgType)
                        .content(content)
                        .replyInThread(message.getThreadId() != null && !message.getThreadId().isBlank())
                        .build())
                .build();
        ReplyMessageResp response = client().im().v1().message().reply(request);
        if (!response.success()) {
            log.warn("Feishu reply message failed, msgType={}, code={}, msg={}, requestId={}",
                    msgType, response.getCode(), response.getMsg(), response.getRequestId());
            return null;
        }
        return response.getData() != null ? response.getData().getMessageId() : null;
    }

    private String createForMessageId(FeishuSendTarget message, String msgType, String content) throws Exception {
        CreateMessageReq request = CreateMessageReq.newBuilder()
                .receiveIdType("chat_id")
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(message.getChatId())
                        .msgType(msgType)
                        .content(content)
                        .build())
                .build();
        CreateMessageResp response = client().im().v1().message().create(request);
        if (!response.success()) {
            log.warn("Feishu create message failed, msgType={}, code={}, msg={}, requestId={}",
                    msgType, response.getCode(), response.getMsg(), response.getRequestId());
            return null;
        }
        return response.getData() != null ? response.getData().getMessageId() : null;
    }

    private boolean reply(FeishuSendTarget message, String msgType, String content) throws Exception {
        return replyForMessageId(message, msgType, content) != null;
    }

    private boolean create(FeishuSendTarget message, String msgType, String content) throws Exception {
        return createForMessageId(message, msgType, content) != null;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private Client client() {
        Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                if (properties.getAppId() == null || properties.getAppId().isBlank()
                        || properties.getAppSecret() == null || properties.getAppSecret().isBlank()) {
                    throw new IllegalStateException("Feishu app-id/app-secret are required");
                }
                client = Client.newBuilder(properties.getAppId(), properties.getAppSecret()).build();
            }
            return client;
        }
    }

    @lombok.Value
    @lombok.Builder
    public static class FeishuSendTarget {
        String chatId;
        String threadId;
        String messageId;
        String text;
    }
}