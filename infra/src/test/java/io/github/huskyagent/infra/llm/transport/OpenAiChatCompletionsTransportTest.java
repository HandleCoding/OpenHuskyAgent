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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatCompletionsTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private String responseBody = "";
    private String responseContentType = "application/json";
    private int statusCode = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
    void completeParsesTextAndUsage() {
        responseBody = """
                {
                  "id": "chatcmpl-1",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "hello world"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}
                }
                """;
        OpenAiChatCompletionsTransport transport = transport();

        LlmResult result = transport.complete(simpleRequest(false));

        assertEquals("/v1/chat/completions", lastPath.get());
        assertEquals("hello world", result.text());
        assertEquals("stop", result.finishReason());
        assertEquals(3, result.usage().promptTokens());
        assertEquals(2, result.usage().completionTokens());
        assertFalse(result.hasToolCalls());
        assertTrue(lastRequestBody.get().contains("\"stream\":false"));
    }

    @Test
    void completeParsesReasoningContentAndToolCalls() {
        responseBody = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "reasoning_content": "thinking hard",
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {"name": "web_search", "arguments": "{\\"q\\":\\"husky\\"}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                    "completion_tokens_details": {"reasoning_tokens": 8}
                  }
                }
                """;

        LlmResult result = transport().complete(simpleRequest(false));

        assertEquals("thinking hard", result.reasoning());
        assertTrue(result.hasToolCalls());
        LlmToolCall call = result.toolCalls().get(0);
        assertEquals("call_1", call.id());
        assertEquals("web_search", call.name());
        assertTrue(call.argumentsJson().contains("husky"));
        assertEquals(8, result.usage().reasoningTokens());
    }

    @Test
    void completeSendsToolsAndMessagesInOpenAiShape() throws Exception {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                """;
        var tools = List.of(new LlmToolDefinition(
                "web_search",
                "search web",
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")));
        LlmRequest request = LlmRequest.builder()
                .model("deepseek-v4-pro")
                .messages(List.of(
                        LlmMessage.system("sys"),
                        LlmMessage.user("hi"),
                        LlmMessage.assistant(null, List.of(new LlmToolCall("c1", "web_search", "{\"q\":\"x\"}"))),
                        LlmMessage.tool("c1", "result")))
                .tools(tools)
                .stream(false)
                .temperature(0.2)
                .maxTokens(64)
                .build();

        transport().complete(request);

        JsonNode body = MAPPER.readTree(lastRequestBody.get());
        assertEquals("deepseek-v4-pro", body.get("model").asText());
        assertEquals(0.2, body.get("temperature").asDouble());
        assertEquals(64, body.get("max_tokens").asInt());
        assertEquals(4, body.get("messages").size());
        assertEquals("tool", body.get("messages").get(3).get("role").asText());
        assertEquals("c1", body.get("messages").get(3).get("tool_call_id").asText());
        assertEquals("web_search", body.get("tools").get(0).get("function").get("name").asText());
    }

    @Test
    void streamAggregatesTextReasoningAndToolCallDeltas() {
        responseContentType = "text/event-stream";
        responseBody = ""
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think \"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"hi \"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"there\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"function\":{\"name\":\"ping\",\"arguments\":\"{\\\"a\\\"\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":1}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}\n\n"
                + "data: [DONE]\n\n";

        List<LlmStreamEvent> events = new ArrayList<>();
        LlmResult result = transport().stream(simpleRequest(true), events::add);

        assertEquals("hi there", result.text());
        assertEquals("think ", result.reasoning());
        assertEquals(1, result.toolCalls().size());
        assertEquals("ping", result.toolCalls().get(0).name());
        assertEquals("{\"a\":1}", result.toolCalls().get(0).argumentsJson());
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmStreamEvent.ReasoningDelta));
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmStreamEvent.TextDelta));
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmStreamEvent.ToolCallDelta t && t.complete()));
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmStreamEvent.Finish));
    }

    @Test
    void httpErrorThrows() {
        statusCode = 401;
        responseBody = "{\"error\":{\"message\":\"bad key\"}}";

        OpenAiChatCompletionsTransport.LlmHttpException ex = assertThrows(
                OpenAiChatCompletionsTransport.LlmHttpException.class,
                () -> transport().complete(simpleRequest(false)));
        assertEquals(401, ex.statusCode());
    }

    @Test
    void protocolAndFactory() {
        assertEquals(LlmProtocol.OPENAI_CHAT_COMPLETIONS, transport().protocol());
        LlmTransportFactory factory = new LlmTransportFactory();
        assertTrue(factory.supports(LlmProtocol.OPENAI_CHAT_COMPLETIONS));
        assertFalse(factory.supports(LlmProtocol.ANTHROPIC_MESSAGES));
        assertNotNull(factory.create(new LlmTransportFactory.ProviderEndpoint(
                LlmProtocol.OPENAI_CHAT_COMPLETIONS, baseUrl, "k", "/v1/chat/completions", null, null, null)));
        assertThrows(UnsupportedOperationException.class, () -> factory.create(new LlmTransportFactory.ProviderEndpoint(
                LlmProtocol.ANTHROPIC_MESSAGES, baseUrl, "k", null, "/v1/messages", "2023-06-01", null)));
    }

    private OpenAiChatCompletionsTransport transport() {
        return new OpenAiChatCompletionsTransport(baseUrl, "test-key", "/v1/chat/completions");
    }

    private static LlmRequest simpleRequest(boolean stream) {
        return LlmRequest.builder()
                .model("deepseek-v4-pro")
                .messages(List.of(LlmMessage.user("hi")))
                .stream(stream)
                .build();
    }
}
