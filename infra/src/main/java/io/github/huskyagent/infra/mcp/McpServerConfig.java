package io.github.huskyagent.infra.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServerConfig(

    String command,

    @JsonProperty("args") List<String> args,

    Map<String, String> env,

    String url,

    String transport,

    Map<String, String> headers,

    @JsonProperty("enabled") Boolean enabled,

    int timeout

) {
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public boolean isStdio() {
        return command != null && !command.isBlank();
    }

    public boolean isHttp() {
        return url != null && !url.isBlank();
    }

    public int getTimeout() {
        return timeout > 0 ? timeout : 120;
    }

    public boolean isSseTransport() {
        return "sse".equalsIgnoreCase(transport);
    }

    public boolean isStreamableHttpTransport() {
        return "streamable-http".equalsIgnoreCase(transport);
    }
}
