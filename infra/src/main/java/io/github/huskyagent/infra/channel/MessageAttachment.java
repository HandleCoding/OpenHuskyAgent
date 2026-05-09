package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

import java.net.URI;
import java.util.Map;

@Value
@Builder
public class MessageAttachment {
    String id;
    Kind kind;
    String mimeType;
    String filename;
    Long sizeBytes;
    byte[] data;
    URI uri;
    Map<String, Object> metadata;

    public enum Kind {
        IMAGE,
        FILE,
        AUDIO,
        VIDEO
    }
}
