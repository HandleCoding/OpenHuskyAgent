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
 * Anthropic Messages API transport (including Anthropic-compatible gateways such as
 * DeepSeek {@code /anthropic} + {@code /v1/messages}).
 */
@Slf4j
public final class AnthropicMessagesTransport implements LlmTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_TOKENS = 8192;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String messagesPath;
    private final String anthropicVersion;
    private final Duration timeout;

    public AnthropicMessagesTransport(String baseUrl,
                                      String apiKey,
                                      String messagesPath,
                                      String anthropicVersion) {
        this(baseUrl, apiKey, messagesPath, anthropicVersion, Duration.ofMinutes(5),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    public AnthropicMessagesTransport(String baseUrl,
                                      String apiKey,
                                      String messagesPath,
                                      String anthropicVersion,
                                      Duration timeout,
                                      HttpClient httpClient) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.apiKey = apiKey != null ? apiKey : "";
        this.messagesPath = normalizePath(
                messagesPath != null && !messagesPath.isBlank() ? messagesPath : "/v1/messages");
        this.anthropicVersion = anthropicVersion != null && !anthropicVersion.isBlank()
                ? anthropicVersion
                : "2023-06-01";
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(5);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public LlmProtocol protocol() {
        return LlmProtocol.ANTHROPIC_MESSAGES;
    }

    @Override
    public LlmCapabilities capabilities() {
        return LlmCapabilities.anthropicMessages();
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        LlmRequest nonStream = withStream(request, false);
        try {
            HttpResponse<String> response = send(nonStream);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmHttpException(response.statusCode(), response.body());
            }
            return parseMessageBody(response.body());
        } catch (LlmHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmTransportException("Anthropic messages completion failed: " + e.getMessage(), e);
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
                sink.accept(new LlmStreamEvent.ErrorEvent(
                        "HTTP " + response.statusCode() + ": " + truncate(body, 500)));
                throw new LlmHttpException(response.statusCode(), body);
            }
            return drainSse(response.body(), sink);
        } catch (LlmHttpException e) {
            throw e;
        } catch (Exception e) {
            sink.accept(new LlmStreamEvent.ErrorEvent(e.getMessage(), e));
            throw new LlmTransportException("Anthropic messages stream failed: " + e.getMessage(), e);
        }
    }

    // ── HTTP ───────────────────────────────────────────────────────────────

    private HttpResponse<String> send(LlmRequest request) throws IOException, InterruptedException {
        String json = MAPPER.writeValueAsString(buildRequestBody(request));
        return httpClient.send(buildHttpRequest(json, false),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<InputStream> sendStream(LlmRequest request) throws IOException, InterruptedException {
        String json = MAPPER.writeValueAsString(buildRequestBody(request));
        return httpClient.send(buildHttpRequest(json, true), HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpRequest buildHttpRequest(String json, boolean stream) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", anthropicVersion)
                // Gateways (e.g. DeepSeek Anthropic path) often accept Bearer as well.
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (stream) {
            builder.header("Accept", "text/event-stream");
        }
        return builder.build();
    }

    private URI endpoint() {
        return URI.create(baseUrl + messagesPath);
    }

    // ── Request body ───────────────────────────────────────────────────────

    ObjectNode buildRequestBody(LlmRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        if (request.model() != null) {
            root.put("model", request.model());
        }
        root.put("stream", request.stream());
        root.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_TOKENS);
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }

        String system = extractSystemPrompt(request.messages());
        if (system != null && !system.isBlank()) {
            root.put("system", system);
        }

        ArrayNode messages = root.putArray("messages");
        appendAnthropicMessages(messages, request.messages());

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (LlmToolDefinition tool : request.tools()) {
                tools.add(toAnthropicTool(tool));
            }
        }
        return root;
    }

    private static String extractSystemPrompt(List<LlmMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage message : messages) {
            if (message.role() == LlmMessage.Role.SYSTEM
                    && message.content() != null
                    && !message.content().isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(message.content());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private void appendAnthropicMessages(ArrayNode out, List<LlmMessage> messages) {
        int i = 0;
        while (i < messages.size()) {
            LlmMessage message = messages.get(i);
            if (message.role() == LlmMessage.Role.SYSTEM) {
                i++;
                continue;
            }
            if (message.role() == LlmMessage.Role.TOOL) {
                // Merge consecutive tool results into one user message (Anthropic requirement).
                ObjectNode user = out.addObject();
                user.put("role", "user");
                ArrayNode content = user.putArray("content");
                while (i < messages.size() && messages.get(i).role() == LlmMessage.Role.TOOL) {
                    LlmMessage toolMsg = messages.get(i);
                    ObjectNode block = content.addObject();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", toolMsg.toolCallId() != null ? toolMsg.toolCallId() : "");
                    block.put("content", toolMsg.content() != null ? toolMsg.content() : "");
                    i++;
                }
                continue;
            }
            if (message.role() == LlmMessage.Role.ASSISTANT) {
                out.add(toAssistantMessage(message));
                i++;
                continue;
            }
            // USER
            ObjectNode user = out.addObject();
            user.put("role", "user");
            user.put("content", message.content() != null ? message.content() : "");
            i++;
        }
    }

    private ObjectNode toAssistantMessage(LlmMessage message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", "assistant");
        boolean hasTools = message.toolCalls() != null && !message.toolCalls().isEmpty();
        boolean hasText = message.content() != null && !message.content().isBlank();
        if (!hasTools) {
            node.put("content", message.content() != null ? message.content() : "");
            return node;
        }
        ArrayNode content = node.putArray("content");
        if (hasText) {
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", message.content());
        }
        for (LlmToolCall call : message.toolCalls()) {
            ObjectNode toolUse = content.addObject();
            toolUse.put("type", "tool_use");
            toolUse.put("id", call.id() != null ? call.id() : "");
            toolUse.put("name", call.name() != null ? call.name() : "");
            toolUse.set("input", parseJsonObject(call.argumentsJson()));
        }
        return node;
    }

    private ObjectNode toAnthropicTool(LlmToolDefinition tool) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", tool.name());
        if (tool.description() != null) {
            node.put("description", tool.description());
        }
        if (tool.parametersSchema() != null) {
            node.set("input_schema", tool.parametersSchema());
        } else {
            node.putObject("input_schema").put("type", "object");
        }
        return node;
    }

    private JsonNode parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (Exception ignored) {
            // fall through
        }
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("_raw", json);
        return wrapper;
    }

    // ── Response parse ─────────────────────────────────────────────────────

    LlmResult parseMessageBody(String body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        return parseMessageNode(root);
    }

    private LlmResult parseMessageNode(JsonNode root) {
        JsonNode content = root.get("content");
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<LlmToolCall> toolCalls = new ArrayList<>();
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                String type = textOrNull(block.get("type"));
                if ("text".equals(type)) {
                    String piece = textOrNull(block.get("text"));
                    if (piece != null) {
                        text.append(piece);
                    }
                } else if ("thinking".equals(type) || "reasoning".equals(type)) {
                    String piece = firstText(block, "thinking", "reasoning", "text");
                    if (piece != null) {
                        reasoning.append(piece);
                    }
                } else if ("tool_use".equals(type)) {
                    String id = textOrNull(block.get("id"));
                    String name = textOrNull(block.get("name"));
                    JsonNode input = block.get("input");
                    String args = input != null && !input.isNull() ? input.toString() : "{}";
                    toolCalls.add(new LlmToolCall(id, name, args));
                }
            }
        }
        String stopReason = textOrNull(root.get("stop_reason"));
        String finish = mapStopReason(stopReason, !toolCalls.isEmpty());
        LlmUsage usage = parseUsage(root.get("usage"));
        return new LlmResult(
                text.isEmpty() ? null : text.toString(),
                reasoning.isEmpty() ? null : reasoning.toString(),
                toolCalls,
                usage,
                finish);
    }

    private LlmResult drainSse(InputStream inputStream, Consumer<LlmStreamEvent> onEvent) throws IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolAcc = new HashMap<>();
        LlmUsage usage = LlmUsage.empty();
        String finishReason = null;
        String sseEventName = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    sseEventName = null;
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    sseEventName = line.substring(6).trim();
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode root;
                try {
                    root = MAPPER.readTree(data);
                } catch (Exception e) {
                    log.debug("Skip non-JSON Anthropic SSE data: {}", truncate(data, 120));
                    continue;
                }
                String type = textOrNull(root.get("type"));
                if (type == null && sseEventName != null) {
                    type = sseEventName;
                }
                if (type == null) {
                    continue;
                }
                switch (type) {
                    case "error" -> {
                        String msg = textOrNull(root.path("error").get("message"));
                        if (msg == null) {
                            msg = root.toString();
                        }
                        onEvent.accept(new LlmStreamEvent.ErrorEvent(msg));
                        throw new LlmTransportException("Anthropic stream error: " + msg);
                    }
                    case "message_start" -> {
                        JsonNode message = root.get("message");
                        if (message != null) {
                            LlmUsage startUsage = parseUsage(message.get("usage"));
                            if (startUsage.promptTokens() != null || startUsage.completionTokens() != null) {
                                usage = mergeUsage(usage, startUsage);
                                onEvent.accept(new LlmStreamEvent.UsageEvent(usage));
                            }
                        }
                    }
                    case "content_block_start" -> {
                        int index = root.path("index").asInt(0);
                        JsonNode block = root.get("content_block");
                        if (block == null) {
                            break;
                        }
                        String blockType = textOrNull(block.get("type"));
                        if ("tool_use".equals(blockType)) {
                            ToolCallAccumulator acc = toolAcc.computeIfAbsent(index, ToolCallAccumulator::new);
                            if (block.hasNonNull("id")) {
                                acc.id = block.get("id").asText();
                            }
                            if (block.hasNonNull("name")) {
                                acc.name = block.get("name").asText();
                            }
                            JsonNode input = block.get("input");
                            if (input != null && input.isObject() && !input.isEmpty()) {
                                acc.arguments.setLength(0);
                                acc.arguments.append(input.toString());
                            }
                        }
                    }
                    case "content_block_delta" -> {
                        int index = root.path("index").asInt(0);
                        JsonNode delta = root.get("delta");
                        if (delta == null || delta.isNull()) {
                            break;
                        }
                        String deltaType = textOrNull(delta.get("type"));
                        if ("text_delta".equals(deltaType) || (deltaType == null && delta.has("text"))) {
                            String piece = textOrNull(delta.get("text"));
                            if (piece != null && !piece.isEmpty()) {
                                text.append(piece);
                                onEvent.accept(new LlmStreamEvent.TextDelta(piece));
                            }
                        } else if ("thinking_delta".equals(deltaType)
                                || "reasoning_delta".equals(deltaType)
                                || delta.has("thinking")
                                || delta.has("reasoning")) {
                            String piece = firstText(delta, "thinking", "reasoning", "text");
                            if (piece != null && !piece.isEmpty()) {
                                reasoning.append(piece);
                                onEvent.accept(new LlmStreamEvent.ReasoningDelta(piece));
                            }
                        } else if ("input_json_delta".equals(deltaType) || delta.has("partial_json")) {
                            String frag = textOrNull(delta.get("partial_json"));
                            if (frag == null) {
                                frag = "";
                            }
                            ToolCallAccumulator acc = toolAcc.computeIfAbsent(index, ToolCallAccumulator::new);
                            acc.arguments.append(frag);
                            onEvent.accept(new LlmStreamEvent.ToolCallDelta(
                                    index, acc.id, acc.name, frag, false));
                        }
                    }
                    case "content_block_stop" -> {
                        int index = root.path("index").asInt(0);
                        ToolCallAccumulator acc = toolAcc.get(index);
                        if (acc != null) {
                            LlmToolCall call = acc.toCall();
                            onEvent.accept(new LlmStreamEvent.ToolCallDelta(
                                    index, call.id(), call.name(), call.argumentsJson(), true));
                        }
                    }
                    case "message_delta" -> {
                        JsonNode delta = root.get("delta");
                        if (delta != null) {
                            String stop = textOrNull(delta.get("stop_reason"));
                            if (stop != null) {
                                finishReason = mapStopReason(stop, !toolAcc.isEmpty());
                            }
                        }
                        JsonNode usageNode = root.get("usage");
                        if (usageNode != null && !usageNode.isNull()) {
                            usage = mergeUsage(usage, parseUsage(usageNode));
                            onEvent.accept(new LlmStreamEvent.UsageEvent(usage));
                        }
                    }
                    case "message_stop", "ping" -> {
                        // no-op
                    }
                    default -> log.trace("Unhandled Anthropic SSE type: {}", type);
                }
            }
        }

        List<LlmToolCall> calls = new ArrayList<>();
        toolAcc.values().stream()
                .sorted(Comparator.comparingInt(a -> a.index))
                .forEach(acc -> calls.add(acc.toCall()));
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

    private static String mapStopReason(String stopReason, boolean hasTools) {
        if (stopReason == null || stopReason.isBlank()) {
            return hasTools ? "tool_calls" : "stop";
        }
        return switch (stopReason) {
            case "end_turn", "stop_sequence", "pause_turn", "refusal" -> "stop";
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            default -> stopReason;
        };
    }

    private static LlmUsage parseUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return LlmUsage.empty();
        }
        Integer prompt = firstInt(usage, "input_tokens", "prompt_tokens");
        Integer completion = firstInt(usage, "output_tokens", "completion_tokens");
        Integer total = intOrNull(usage.get("total_tokens"));
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }
        Integer cached = firstInt(usage, "cache_read_input_tokens", "cached_tokens");
        Integer reasoning = firstInt(usage, "reasoning_tokens");
        return new LlmUsage(prompt, completion, total, cached, reasoning);
    }

    private static LlmUsage mergeUsage(LlmUsage base, LlmUsage next) {
        if (next == null) {
            return base != null ? base : LlmUsage.empty();
        }
        if (base == null) {
            return next;
        }
        Integer prompt = next.promptTokens() != null ? next.promptTokens() : base.promptTokens();
        Integer completion = next.completionTokens() != null ? next.completionTokens() : base.completionTokens();
        Integer total = next.totalTokens() != null ? next.totalTokens() : base.totalTokens();
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }
        Integer cached = next.cachedPromptTokens() != null ? next.cachedPromptTokens() : base.cachedPromptTokens();
        Integer reasoning = next.reasoningTokens() != null ? next.reasoningTokens() : base.reasoningTokens();
        return new LlmUsage(prompt, completion, total, cached, reasoning);
    }

    private static Integer firstInt(JsonNode node, String... fields) {
        for (String field : fields) {
            Integer v = intOrNull(node.get(field));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.intValue();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String v = textOrNull(node.get(field));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
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
            String args = arguments.isEmpty() ? "{}" : arguments.toString();
            return new LlmToolCall(
                    id != null ? id : "toolu_" + index,
                    name != null ? name : "",
                    args);
        }
    }
}
