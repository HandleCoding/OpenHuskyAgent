package io.github.huskyagent.infra.llm.api;

/**
 * Multimodal content part for a chat message (text or image).
 */
public record LlmContentPart(
        Kind kind,
        String text,
        String mimeType,
        String dataBase64,
        String url
) {
    public enum Kind {
        TEXT,
        IMAGE_BASE64,
        IMAGE_URL
    }

    public static LlmContentPart text(String text) {
        return new LlmContentPart(Kind.TEXT, text != null ? text : "", null, null, null);
    }

    public static LlmContentPart imageBase64(String mimeType, String dataBase64) {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("image mimeType is required");
        }
        if (dataBase64 == null || dataBase64.isBlank()) {
            throw new IllegalArgumentException("image dataBase64 is required");
        }
        return new LlmContentPart(Kind.IMAGE_BASE64, null, mimeType.trim(), dataBase64, null);
    }

    public static LlmContentPart imageUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("image url is required");
        }
        return new LlmContentPart(Kind.IMAGE_URL, null, null, null, url.trim());
    }

    public boolean isImage() {
        return kind == Kind.IMAGE_BASE64 || kind == Kind.IMAGE_URL;
    }
}
