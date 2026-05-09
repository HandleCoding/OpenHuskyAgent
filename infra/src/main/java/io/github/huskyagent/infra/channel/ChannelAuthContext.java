package io.github.huskyagent.infra.channel;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class ChannelAuthContext {
    String connectionId;
    Map<String, String> headers;
    String rawBody;
    Map<String, Object> attributes;
}
