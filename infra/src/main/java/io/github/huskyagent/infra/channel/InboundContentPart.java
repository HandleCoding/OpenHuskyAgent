package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class InboundContentPart {
    Kind kind;
    String text;
    MessageAttachment attachment;
    Map<String, Object> metadata;

    public enum Kind {
        TEXT,
        IMAGE,
        FILE,
        AUDIO,
        VIDEO
    }

    public static InboundContentPart text(String text) {
        return InboundContentPart.builder()
                .kind(Kind.TEXT)
                .text(text)
                .build();
    }

    public static InboundContentPart attachment(MessageAttachment attachment) {
        return InboundContentPart.builder()
                .kind(toPartKind(attachment != null ? attachment.getKind() : null))
                .attachment(attachment)
                .build();
    }

    private static Kind toPartKind(MessageAttachment.Kind kind) {
        if (kind == null) {
            return Kind.FILE;
        }
        return switch (kind) {
            case IMAGE -> Kind.IMAGE;
            case AUDIO -> Kind.AUDIO;
            case VIDEO -> Kind.VIDEO;
            case FILE -> Kind.FILE;
        };
    }
}
