package io.github.huskyagent.infra.llm.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.huskyagent.infra.llm.api.LlmMessage;
import io.github.huskyagent.infra.llm.api.LlmProtocol;
import io.github.huskyagent.infra.llm.api.LlmRequest;
import io.github.huskyagent.infra.llm.api.LlmResult;
import io.github.huskyagent.infra.llm.api.LlmStreamEvent;
import io.github.huskyagent.infra.llm.api.LlmToolCall;
import io.github.huskyagent.infra.llm.api.LlmToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicMessagesTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private String responseBody = "";
    private String responseContentType = "application/json";
    private int statusCode = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastHeaders.clear();
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    lastHeaders.put(k.toLowerCase(), v.get(0));
                }
            });
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", responseContentType);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void completeParsesTextUsageAndHeaders() {
        responseBody = """
                {
                  "id": "msg_1",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "hello claude"}],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 12, "output_tokens": 4}
                }
                """;

        LlmResult result = transport().complete(simpleRequest(false));

        assertEquals("/v1/messages", lastPath.get());
        assertEquals("hello claude", result.text());
        assertEquals("stop", result.finishReason());
        assertEquals(12, result.usage().promptTokens());
        assertEquals(4, result.usage().completionTokens());
        assertEquals(16, result.usage().totalTokens());
        assertEquals("test-key", lastHeaders.get("x-api-key"));
        assertEquals("2023-06-01", lastHeaders.get("anthropic-version"));
        assertTrue(lastHeaders.getOrDefault("authorization", "").contains("test-key"));
    }

    @Test
    void completeParsesThinkingAndToolUse() {
        responseBody = """
                {
                  "content": [
                    {"type": "thinking", "thinking": "plan next step"},
                    {"type": "text", "text": "calling tool"},
                    {"type": "tool_use", "id": "toolu_1", "name": "web_search", "input": {"q": "husky"}}
                  ],
                  "stop_reason": "tool_use",
                  "usage": {
                    "input_tokens": 10,
                    "output_tokens": 20,
                    "cache_read_input_tokens": 3
                  }
                }
                """;

        LlmResult result = transport().complete(simpleRequest(false));

        assertEquals("plan next step", result.reasoning());
        assertEquals("calling tool", result.text());
        assertTrue(result.hasToolCalls());
        LlmToolCall call = result.toolCalls().get(0);
        assertEquals("toolu_1", call.id());
        assertEquals("web_search", call.name());
        assertTrue(call.argumentsJson().contains("husky"));
        assertEquals("tool_calls", result.finishReason());
        assertEquals(3, result.usage().cachedPromptTokens());
    }

    @Test
    void completeBuildsAnthropicMessageShape() throws Exception {
        responseBody = """
                {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}
                """;
        var tools = List.of(new LlmToolDefinition(
                "web_search",
                "search web",
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")));
        LlmRequest request = LlmRequest.builder()
                .model("claude-sonnet-4-5")
                .messages(List.of(
                        LlmMessage.system("sys prompt"),
                        LlmMessage.user("hi"),
                        LlmMessage.assistant(null, List.of(new LlmToolCall("c1", "web_search", "{\"q\":\"x\"}"))),
                        LlmMessage.tool("c1", "result-a"),
                        LlmMessage.tool("c2", "result-b"),
                        LlmMessage.user("continue")))
                .tools(tools)
                .stream(false)
                .temperature(0.2)
                .maxTokens(64)
                .build();

        transport().complete(request);

        JsonNode body = MAPPER.readTree(lastRequestBody.get());
        assertEquals("claude-sonnet-4-5", body.get("model").asText());
        assertEquals(0.2, body.get("temperature").asDouble());
        assertEquals(64, body.get("max_tokens").asInt());
        assertEquals("sys prompt", body.get("system").asText());
        assertFalse(body.get("stream").asBoolean());

        // system stripped from messages; tool results merged into one user message
        assertEquals(4, body.get("messages").size());
        assertEquals("user", body.get("messages").get(0).get("role").asText());
        assertEquals("hi", body.get("messages").get(0).get("content").asText());

        JsonNode assistant = body.get("messages").get(1);
        assertEquals("assistant", assistant.get("role").asText());
        assertEquals("tool_use", assistant.get("content").get(0).get("type").asText());
        assertEquals("c1", assistant.get("content").get(0).get("id").asText());
        assertEquals("web_search", assistant.get("content").get(0).get("name").asText());
        assertEquals("x", assistant.get("content").get(0).get("input").get("q").asText());

        JsonNode toolUser = body.get("messages").get(2);
        assertEquals("user", toolUser.get("role").asText());
        assertEquals(2, toolUser.get("content").size());
        assertEquals("tool_result", toolUser.get("content").get(0).get("type").asText());
        assertEquals("c1", toolUser.get("content").get(0).get("tool_use_id").asText());
        assertEquals("result-a", toolUser.get("content").get(0).get("content").asText());
        assertEquals("c2", toolUser.get("content").get(1).get("tool_use_id").asText());

        assertEquals("continue", body.get("messages").get(3).get("content").asText());
        assertEquals("web_search", body.get("tools").get(0).get("name").asText());
        assertEquals("object", body.get("tools").get(0).get("input_schema").get("type").asText());
        assertFalse(body.get("tools").get(0).has("function"));
    }

    @Test
    void completeDefaultsMaxTokensWhenMissing() throws Exception {
        responseBody = """
                {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}
                """;
        transport().complete(simpleRequest(false));
        JsonNode body = MAPPER.readTree(lastRequestBody.get());
        assertEquals(8192, body.get("max_tokens").asInt());
    }

    @Test
    void streamAggregatesTextThinkingAndToolCallDeltas() {
        responseContentType = "text/event-stream";
        responseBody = ""
                + "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"think \"}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi \"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"there\"}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"ping\",\"input\":{}}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\":1}\"}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":2}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":9}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";

        List<LlmStreamEvent> events = new ArrayList<>();
        LlmResult result = transport().stream(simpleRequest(true), events::add);

        assertEquals("hi there", result.text());
        assertEquals("think ", result.reasoning());
        assertEquals(1, result.toolCalls().size());
        assertEquals("ping", result.toolCalls().get(0).name());
        assertEquals("toolu_9", result.toolCalls().get(0).id());
        assertEquals("{\"a\":1}", result.toolCalls().get(0).argumentsJson());
        assertEquals("tool_calls", result.finishReason());
        assertEquals(5, result.usage().promptTokens());
        assertEquals(9, result.usage().completionTokens());
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmStreamEvent.ReasoningDelta));
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmStreamEvent.TextDelta));
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmStreamEvent.ToolCallDelta t && t.complete()));
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmStreamEvent.Finish));
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmStreamEvent.UsageEvent));
    }

    @Test
    void httpErrorThrows() {
        statusCode = 401;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}";

        LlmHttpException ex = assertThrows(
                LlmHttpException.class,
                () -> transport().complete(simpleRequest(false)));
        assertEquals(401, ex.statusCode());
    }

    @Test
    void protocolAndFactory() {
        assertEquals(LlmProtocol.ANTHROPIC_MESSAGES, transport().protocol());
        assertTrue(transport().capabilities().streaming());
        assertTrue(transport().capabilities().tools());

        LlmTransportFactory factory = new LlmTransportFactory();
        var created = factory.create(new LlmTransportFactory.ProviderEndpoint(
                LlmProtocol.ANTHROPIC_MESSAGES, baseUrl, "k", null, "/v1/messages", "2023-06-01", null));
        assertNotNull(created);
        assertEquals(LlmProtocol.ANTHROPIC_MESSAGES, created.protocol());
    }

    @Test
    void customMessagesPath() {
        responseBody = """
                {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}
                """;
        AnthropicMessagesTransport custom = new AnthropicMessagesTransport(
                baseUrl, "k", "/anthropic/v1/messages", "2023-06-01");
        custom.complete(simpleRequest(false));
        assertEquals("/anthropic/v1/messages", lastPath.get());
    }

    private AnthropicMessagesTransport transport() {
        return new AnthropicMessagesTransport(baseUrl, "test-key", "/v1/messages", "2023-06-01");
    }

    private static LlmRequest simpleRequest(boolean stream) {
        return LlmRequest.builder()
                .model("claude-sonnet-4-5")
                .messages(List.of(LlmMessage.user("hi")))
                .stream(stream)
                .build();
    }
}
