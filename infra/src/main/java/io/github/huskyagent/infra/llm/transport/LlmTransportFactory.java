package io.github.huskyagent.infra.llm.transport;

import io.github.huskyagent.infra.llm.api.LlmProtocol;
import io.github.huskyagent.infra.llm.api.LlmTransport;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Creates {@link LlmTransport} instances by protocol. Register future protocols here
 * without changing CallModelNode.
 */
public final class LlmTransportFactory {

    @FunctionalInterface
    public interface Creator {
        LlmTransport create(ProviderEndpoint endpoint);
    }

    /**
     * Minimal endpoint config needed to construct a transport (decoupled from Spring {@code LlmProperties}).
     */
    public record ProviderEndpoint(
            LlmProtocol protocol,
            String baseUrl,
            String apiKey,
            String completionsPath,
            String messagesPath,
            String anthropicVersion,
            Duration timeout
    ) {
        public ProviderEndpoint {
            if (protocol == null) {
                protocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS;
            }
            if (timeout == null) {
                timeout = Duration.ofMinutes(5);
            }
        }
    }

    private final Map<LlmProtocol, Creator> creators = new EnumMap<>(LlmProtocol.class);

    public LlmTransportFactory() {
        register(LlmProtocol.OPENAI_CHAT_COMPLETIONS, endpoint ->
                new OpenAiChatCompletionsTransport(
                        endpoint.baseUrl(),
                        endpoint.apiKey(),
                        endpoint.completionsPath() != null ? endpoint.completionsPath() : "/v1/chat/completions",
                        endpoint.timeout(),
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(30))
                                .build()));
        // Anthropic: registered as unsupported until PR3
        register(LlmProtocol.ANTHROPIC_MESSAGES, endpoint -> {
            throw new UnsupportedOperationException(
                    "anthropic_messages transport is not implemented yet; use openai_chat_completions "
                            + "or wait for AnthropicMessagesTransport");
        });
    }

    public void register(LlmProtocol protocol, Creator creator) {
        creators.put(Objects.requireNonNull(protocol), Objects.requireNonNull(creator));
    }

    public LlmTransport create(ProviderEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        Creator creator = creators.get(endpoint.protocol());
        if (creator == null) {
            throw new IllegalArgumentException("No LlmTransport registered for protocol " + endpoint.protocol());
        }
        return creator.create(endpoint);
    }

    public boolean supports(LlmProtocol protocol) {
        Creator creator = creators.get(protocol);
        if (creator == null) {
            return false;
        }
        // anthropic registered but throws — treat as not ready for production create
        return protocol != LlmProtocol.ANTHROPIC_MESSAGES;
    }
}
