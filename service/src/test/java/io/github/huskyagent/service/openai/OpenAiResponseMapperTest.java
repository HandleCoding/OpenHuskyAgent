package io.github.huskyagent.service.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.application.ChatResult;
import io.github.huskyagent.infra.context.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiResponseMapperTest {

    private final OpenAiResponseMapper mapper = new OpenAiResponseMapper();

    @Test
    void mapsNonStreamingSuccess() {
        OpenAiWireResponses.ChatCompletion response = mapper.success(
                "chatcmpl-test", 123L, "assistant", "hello", new TokenUsage(1, 2, 3));

        assertEquals("chat.completion", response.object());
        assertEquals("assistant", response.model());
        assertEquals("assistant", response.choices().get(0).message().role());
        assertEquals("hello", response.choices().get(0).message().content());
        assertEquals("stop", response.choices().get(0).finishReason());
        assertEquals(3, response.usage().totalTokens());
    }

    @Test
    void usesZeroUsageWhenMissing() {
        OpenAiWireResponses.ChatCompletion response = mapper.success("chatcmpl-test", 123L, "assistant", "hello", null);

        assertEquals(0, response.usage().promptTokens());
        assertEquals(0, response.usage().completionTokens());
        assertEquals(0, response.usage().totalTokens());
    }

    @Test
    void mapsStreamingContentChunk() throws Exception {
        OpenAiWireResponses.ChatCompletionChunk chunk = mapper.contentChunk("chatcmpl-test", 123L, "assistant", "hi");
        String json = new ObjectMapper().writeValueAsString(chunk);

        assertTrue(json.contains("\"object\":\"chat.completion.chunk\""));
        assertTrue(json.contains("\"content\":\"hi\""));
    }

    @Test
    void mapsProtocolError() {
        var response = mapper.protocolError(new OpenAiProtocolException("bad", "model", "model_not_found"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("model_not_found", response.getBody().error().code());
    }

    @Test
    void mapsRateLimitedTo429WithRetryAfter() {
        ChatResult limited = ChatResult.rateLimited("Rate limit exceeded for this agent.", 5L);
        var response = mapper.runtimeError(limited);

        assertEquals(429, response.getStatusCode().value());
        assertEquals("rate_limit_error", response.getBody().error().type());
        assertEquals("rate_limited", response.getBody().error().code());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void mapsRateLimitedWithoutRetryAfterHeaderWhenMissing() {
        ChatResult limited = new ChatResult(null, false, "Rate limit exceeded",
                ChatResult.ErrorCode.RATE_LIMITED, null, false, null, null);
        var response = mapper.runtimeError(limited);

        assertEquals(429, response.getStatusCode().value());
        assertNull(response.getHeaders().getFirst("Retry-After"));
    }
}
