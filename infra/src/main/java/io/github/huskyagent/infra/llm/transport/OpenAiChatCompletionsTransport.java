package io.github.huskyagent.infra.llm.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.huskyagent.infra.llm.api.LlmCapabilities;
import io.github.huskyagent.infra.llm.api.LlmMessage;
import io.github.huskyagent.infra.llm.api.LlmProtocol;
import io.github.huskyagent.infra.llm.api.LlmRequest;
import io.github.huskyagent.infra.llm.api.LlmResult;
import io.github.huskyagent.infra.llm.api.LlmStreamEvent;
import io.github.huskyagent.infra.llm.api.LlmToolCall;
import io.github.huskyagent.infra.llm.api.LlmToolDefinition;
import io.github.huskyagent.infra.llm.api.LlmTransport;
import io.github.huskyagent.infra.llm.api.LlmUsage;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * OpenAI Chat Completions protocol (including OpenAI-compatible gateways such as DeepSeek).
 */
@Slf4j
public final class OpenAiChatCompletionsTransport implements LlmTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String completionsPath;
    private final Duration timeout;

    public OpenAiChatCompletionsTransport(String baseUrl, String apiKey, String completionsPath) {
        this(baseUrl, apiKey, completionsPath, Duration.ofMinutes(5),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    public OpenAiChatCompletionsTransport(String baseUrl,
                                          String apiKey,
                                          String completionsPath,
                                          Duration timeout,
                                          HttpClient httpClient) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.apiKey = apiKey != null ? apiKey : "";
        this.completionsPath = normalizePath(
                completionsPath != null && !completionsPath.isBlank()
                        ? completionsPath
                        : "/v1/chat/completions");
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(5);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public LlmProtocol protocol() {
        return LlmProtocol.OPENAI_CHAT_COMPLETIONS;
    }

    @Override
    public LlmCapabilities capabilities() {
        return LlmCapabilities.openaiChatCompletions();
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        LlmRequest nonStream = withStream(request, false);
        try {
            HttpResponse<String> response = send(nonStream);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmHttpException(response.statusCode(), response.body());
            }
            return parseCompletionBody(response.body());
        } catch (LlmHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmTransportException("OpenAI chat completion failed: " + e.getMessage(), e);
        }
    }

    @Override
    public LlmResult stream(LlmRequest request, Consumer<LlmStreamEvent> onEvent) {
        Consumer<LlmStreamEvent> sink = onEvent != null ? onEvent : ev -> {
        };
        LlmRequest streamReq = withStream(request, true);
        try {
            HttpResponse<InputStream> response = sendStream(streamReq);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                LlmStreamEvent.ErrorEvent err = new LlmStreamEvent.ErrorEvent(
                        "HTTP " + response.statusCode() + ": " + truncate(body, 500));
                sink.accept(err);
                throw new LlmHttpException(response.statusCode(), body);
            }
            return drainSse(response.body(), sink);
        } catch (LlmHttpException e) {
            throw e;
        } catch (Exception e) {
            sink.accept(new LlmStreamEvent.ErrorEvent(e.getMessage(), e));
            throw new LlmTransportException("OpenAI chat stream failed: " + e.getMessage(), e);
        }
    }

    // ── HTTP ───────────────────────────────────────────────────────────────

    private HttpResponse<String> send(LlmRequest request) throws IOException, InterruptedException {
        String json = MAPPER.writeValueAsString(buildRequestBody(request));
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint())
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<InputStream> sendStream(LlmRequest request) throws IOException, InterruptedException {
        String json = MAPPER.writeValueAsString(buildRequestBody(request));
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint())
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    }

    private URI endpoint() {
        return URI.create(baseUrl + completionsPath);
    }

    // ── Request body ───────────────────────────────────────────────────────

    ObjectNode buildRequestBody(LlmRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        if (request.model() != null) {
            root.put("model", request.model());
        }
        root.put("stream", request.stream());
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            root.put("max_tokens", request.maxTokens());
        }
        ArrayNode messages = root.putArray("messages");
        for (LlmMessage message : request.messages()) {
            messages.add(toOpenAiMessage(message));
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (LlmToolDefinition tool : request.tools()) {
                tools.add(toOpenAiTool(tool));
            }
        }
        return root;
    }

    private ObjectNode toOpenAiMessage(LlmMessage message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", roleName(message.role()));
        if (message.role() == LlmMessage.Role.TOOL) {
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
            node.put("content", message.content() != null ? message.content() : "");
            return node;
        }
        if (message.role() == LlmMessage.Role.ASSISTANT
                && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            if (message.content() != null) {
                node.put("content", message.content());
            } else {
                node.putNull("content");
            }
            ArrayNode toolCalls = node.putArray("tool_calls");
            for (LlmToolCall call : message.toolCalls()) {
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", call.id() != null ? call.id() : "");
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", call.name() != null ? call.name() : "");
                fn.put("arguments", call.argumentsJson() != null ? call.argumentsJson() : "{}");
            }
            return node;
        }
        node.put("content", message.content() != null ? message.content() : "");
        return node;
    }

    private ObjectNode toOpenAiTool(LlmToolDefinition tool) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "function");
        ObjectNode fn = node.putObject("function");
        fn.put("name", tool.name());
        if (tool.description() != null) {
            fn.put("description", tool.description());
        }
        if (tool.parametersSchema() != null) {
            fn.set("parameters", tool.parametersSchema());
        } else {
            fn.putObject("parameters").put("type", "object");
        }
        return node;
    }

    private static String roleName(LlmMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    // ── Response parse ─────────────────────────────────────────────────────

    LlmResult parseCompletionBody(String body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = textOrNull(message.get("content"));
        String reasoning = textOrNull(message.get("reasoning_content"));
        List<LlmToolCall> toolCalls = parseToolCalls(message.get("tool_calls"));
        String finish = textOrNull(choice.get("finish_reason"));
        LlmUsage usage = parseUsage(root.get("usage"));
        return new LlmResult(content, reasoning, toolCalls, usage, finish);
    }

    private LlmResult drainSse(InputStream inputStream, Consumer<LlmStreamEvent> onEvent) throws IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolAcc = new HashMap<>();
        LlmUsage usage = LlmUsage.empty();
        String finishReason = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode root;
                try {
                    root = MAPPER.readTree(data);
                } catch (Exception e) {
                    log.debug("Skip non-JSON SSE data: {}", truncate(data, 120));
                    continue;
                }
                JsonNode usageNode = root.get("usage");
                if (usageNode != null && !usageNode.isNull()) {
                    usage = parseUsage(usageNode);
                    onEvent.accept(new LlmStreamEvent.UsageEvent(usage));
                }
                JsonNode choice = root.path("choices").path(0);
                if (choice.isMissingNode()) {
                    continue;
                }
                String fr = textOrNull(choice.get("finish_reason"));
                if (fr != null && !fr.isBlank() && !"null".equalsIgnoreCase(fr)) {
                    finishReason = fr;
                }
                JsonNode delta = choice.get("delta");
                if (delta == null || delta.isNull()) {
                    // some gateways put message on non-delta final chunk
                    delta = choice.get("message");
                }
                if (delta == null || delta.isNull()) {
                    continue;
                }
                String reasoningPiece = textOrNull(delta.get("reasoning_content"));
                if (reasoningPiece != null && !reasoningPiece.isEmpty()) {
                    reasoning.append(reasoningPiece);
                    onEvent.accept(new LlmStreamEvent.ReasoningDelta(reasoningPiece));
                }
                String contentPiece = textOrNull(delta.get("content"));
                if (contentPiece != null && !contentPiece.isEmpty()) {
                    text.append(contentPiece);
                    onEvent.accept(new LlmStreamEvent.TextDelta(contentPiece));
                }
                JsonNode toolCalls = delta.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        int index = tc.path("index").asInt(0);
                        ToolCallAccumulator acc = toolAcc.computeIfAbsent(index, ToolCallAccumulator::new);
                        if (tc.hasNonNull("id")) {
                            acc.id = tc.get("id").asText();
                        }
                        JsonNode fn = tc.get("function");
                        if (fn != null) {
                            if (fn.hasNonNull("name")) {
                                acc.name = fn.get("name").asText();
                            }
                            if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                                String frag = fn.get("arguments").asText("");
                                acc.arguments.append(frag);
                                onEvent.accept(new LlmStreamEvent.ToolCallDelta(
                                        index, acc.id, acc.name, frag, false));
                            }
                        }
                    }
                }
            }
        }

        List<LlmToolCall> calls = new ArrayList<>();
        toolAcc.values().stream()
                .sorted(Comparator.comparingInt(a -> a.index))
                .forEach(acc -> {
                    LlmToolCall call = acc.toCall();
                    calls.add(call);
                    onEvent.accept(new LlmStreamEvent.ToolCallDelta(
                            acc.index, call.id(), call.name(), call.argumentsJson(), true));
                });
        if (finishReason == null) {
            finishReason = calls.isEmpty() ? "stop" : "tool_calls";
        }
        onEvent.accept(new LlmStreamEvent.Finish(finishReason));
        return new LlmResult(
                text.isEmpty() ? null : text.toString(),
                reasoning.isEmpty() ? null : reasoning.toString(),
                calls,
                usage,
                finishReason);
    }

    private static List<LlmToolCall> parseToolCalls(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
            return List.of();
        }
        List<LlmToolCall> result = new ArrayList<>();
        for (JsonNode tc : toolCalls) {
            String id = textOrNull(tc.get("id"));
            JsonNode fn = tc.get("function");
            String name = fn != null ? textOrNull(fn.get("name")) : null;
            String args = fn != null ? textOrNull(fn.get("arguments")) : null;
            result.add(new LlmToolCall(id, name, args != null ? args : "{}"));
        }
        return List.copyOf(result);
    }

    private static LlmUsage parseUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return LlmUsage.empty();
        }
        Integer prompt = intOrNull(usage.get("prompt_tokens"));
        Integer completion = intOrNull(usage.get("completion_tokens"));
        Integer total = intOrNull(usage.get("total_tokens"));
        Integer cached = null;
        JsonNode details = usage.get("prompt_tokens_details");
        if (details != null && details.has("cached_tokens")) {
            cached = intOrNull(details.get("cached_tokens"));
        }
        Integer reasoning = null;
        JsonNode cdetails = usage.get("completion_tokens_details");
        if (cdetails != null && cdetails.has("reasoning_tokens")) {
            reasoning = intOrNull(cdetails.get("reasoning_tokens"));
        }
        return new LlmUsage(prompt, completion, total, cached, reasoning);
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.intValue();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        // some providers send content as array of parts
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : node) {
                if (part.has("text")) {
                    sb.append(part.get("text").asText(""));
                } else if (part.isTextual()) {
                    sb.append(part.asText());
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return node.asText(null);
    }

    private static LlmRequest withStream(LlmRequest request, boolean stream) {
        return LlmRequest.builder()
                .model(request.model())
                .messages(request.messages())
                .tools(request.tools())
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .stream(stream)
                .extra(request.extra())
                .build();
    }

    private static String trimTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String normalizePath(String path) {
        if (!path.startsWith("/")) {
            return "/" + path;
        }
        return path;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static final class ToolCallAccumulator {
        private final int index;
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private ToolCallAccumulator(int index) {
            this.index = index;
        }

        private LlmToolCall toCall() {
            return new LlmToolCall(
                    id != null ? id : "call_" + index,
                    name != null ? name : "",
                    arguments.isEmpty() ? "{}" : arguments.toString());
        }
    }

    public static class LlmTransportException extends RuntimeException {
        public LlmTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class LlmHttpException extends RuntimeException {
        private final int statusCode;
        private final String body;

        public LlmHttpException(int statusCode, String body) {
            super("LLM HTTP " + statusCode + ": " + truncate(body, 300));
            this.statusCode = statusCode;
            this.body = body;
        }

        public int statusCode() {
            return statusCode;
        }

        public String body() {
            return body;
        }
    }
}
