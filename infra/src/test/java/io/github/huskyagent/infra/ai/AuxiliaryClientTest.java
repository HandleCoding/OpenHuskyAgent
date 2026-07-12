package io.github.huskyagent.infra.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.huskyagent.infra.config.AgentConfig;
import io.github.huskyagent.infra.llm.api.LlmTransport;
import io.github.huskyagent.infra.llm.transport.OpenAiChatCompletionsTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuxiliaryClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private String responseBody = """
            {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
            """;

    private AgentConfig.AuxiliaryConfig auxiliaryConfig;
    private LlmTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        auxiliaryConfig = new AgentConfig.AuxiliaryConfig();
        auxiliaryConfig.setModel("test-model");
        auxiliaryConfig.setTemperature(0.3);
        auxiliaryConfig.setMaxTokens(100);
        auxiliaryConfig.setWebSummaryMaxTokens(200);
        transport = new OpenAiChatCompletionsTransport(baseUrl, "key", "/v1/chat/completions");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private AuxiliaryClient client() {
        return new AuxiliaryClient(transport, auxiliaryConfig);
    }

    @Test
    void testGenerateTitleWithNullInput() {
        AuxiliaryClient c = client();
        assertEquals("New Conversation", c.generateTitle(null));
        assertEquals("New Conversation", c.generateTitle(""));
        assertEquals("New Conversation", c.generateTitle("   "));
    }

    @Test
    void testSummarizeWithNullInput() {
        AuxiliaryClient c = client();
        assertEquals("", c.summarize(null));
        assertEquals("", c.summarize(""));
        assertEquals("", c.summarize("   "));
    }

    @Test
    void testTranslateWithNullInput() {
        AuxiliaryClient c = client();
        assertEquals("", c.translate(null, "Chinese"));
        assertEquals("", c.translate("", "Chinese"));
    }

    @Test
    void testExtractKeyInfoWithNullInput() {
        AuxiliaryClient c = client();
        assertEquals("", c.extractKeyInfo(null, "test"));
        assertEquals("", c.extractKeyInfo("", "test"));
    }

    @Test
    void testAnalyzeImageReturnsClearErrorForEmptyImage() {
        assertTrue(client().analyzeImage(new byte[0], "image/png", "describe it").contains("image data is empty"));
    }

    @Test
    void testAnalyzeImageReturnsClearErrorForMissingMimeType() {
        assertTrue(client().analyzeImage(new byte[]{1}, "", "describe it").contains("MIME type is required"));
    }

    @Test
    void testAnalyzeImageReturnsClearErrorForMissingQuestion() {
        assertTrue(client().analyzeImage(new byte[]{1}, "image/png", "").contains("question is required"));
    }

    @Test
    void completeTextSendsOpenAiRequestAndParsesReply() throws Exception {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"hello-aux"},"finish_reason":"stop"}]}
                """;
        String text = client().completeText("ping", 32);
        assertEquals("hello-aux", text);
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("test-model", body.get("model").asText());
        assertEquals(false, body.get("stream").asBoolean());
        assertEquals("ping", body.get("messages").get(0).get("content").asText());
    }

    @Test
    void analyzeImageSendsMultimodalImageUrlPart() throws Exception {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"a red square"},"finish_reason":"stop"}]}
                """;
        byte[] png = new byte[]{1, 2, 3, 4};
        String result = client().analyzeImage(png, "image/png", "what color?");
        assertEquals("a red square", result);

        JsonNode body = MAPPER.readTree(lastBody.get());
        JsonNode content = body.get("messages").get(0).get("content");
        assertTrue(content.isArray());
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("image_url", content.get(1).get("type").asText());
        String dataUrl = content.get(1).get("image_url").get("url").asText();
        assertTrue(dataUrl.startsWith("data:image/png;base64,"));
        String expectedB64 = Base64.getEncoder().encodeToString(png);
        assertTrue(dataUrl.endsWith(expectedB64));
    }

    @Test
    void createUsesIndependentEndpointWhenConfigured() throws Exception {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"indep"},"finish_reason":"stop"}]}
                """;
        AgentConfig.AuxiliaryConfig cfg = new AgentConfig.AuxiliaryConfig();
        cfg.setModel("aux-model");
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey("aux-key");
        cfg.setCompletionsPath("/v1/chat/completions");
        cfg.setMaxTokens(50);
        AuxiliaryClient c = AuxiliaryClient.create(cfg, "http://unused", "main-key", "/v1/chat/completions");
        assertEquals("indep", c.completeText("hi"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("aux-model", body.get("model").asText());
    }
}
