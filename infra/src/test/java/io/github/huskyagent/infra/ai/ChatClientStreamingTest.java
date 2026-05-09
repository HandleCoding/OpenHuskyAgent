package io.github.huskyagent.infra.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@EnabledIf("isApiConfigured")
class ChatClientStreamingTest {

    private static final String BASE_URL  = System.getenv("OPENAI_BASE_URL") != null
            ? System.getenv("OPENAI_BASE_URL") : "https://cloud.infini-ai.com/maas/coding";
    private static final String API_KEY   = System.getenv("OPENAI_API_KEY") != null
            ? System.getenv("OPENAI_API_KEY") : "";
    private static final String MODEL     = System.getenv("OPENAI_MODEL") != null
            ? System.getenv("OPENAI_MODEL") : "glm-5";

    static boolean isApiConfigured() {
        return System.getenv("OPENAI_API_KEY") != null && !System.getenv("OPENAI_API_KEY").isEmpty()
                || API_KEY != null && !API_KEY.isEmpty();
    }

    private ChatClient chatClient;
    private ChatClient chatClientWithTools;

    private static final String TOOL_NAME = "get_weather";
    private static final String TOOL_DESC = "Get weather information for the specified city";
    private static final String TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "city": { "type": "string", "description": "city name" }
              },
              "required": ["city"]
            }
            """;

    @BeforeEach
    void setUp() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(30));
        requestFactory.setReadTimeout(Duration.ofMinutes(3));

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(BASE_URL)
                .apiKey(API_KEY)
                .completionsPath("/v1/chat/completions")
                .restClientBuilder(restClientBuilder)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(MODEL)
                        .temperature(0.7)
                        .build())
                .build();

        chatClient = ChatClient.builder(chatModel).build();

        chatClientWithTools = ChatClient.builder(chatModel)
                .defaultOptions(ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build())
                .defaultToolCallbacks(buildWeatherToolCallback())
                .build();
    }

    @Test
    void scenario1a_normalChat_call() {
        System.out.println("\n════════════════════════════════════════");
        System.out.println("Scenario 1a: normal chat — .call()");
        System.out.println("════════════════════════════════════════");

        long start = System.currentTimeMillis();

        ChatResponse response = chatClient.prompt()
                .messages(new UserMessage("Describe the Java language in one sentence."))
                .call()
                .chatResponse();

        long elapsed = System.currentTimeMillis() - start;
        AssistantMessage output = response.getResult().getOutput();

        System.out.println("✅ Response time (waiting for full result): " + elapsed + "ms");
        System.out.println("   hasToolCalls: " + output.hasToolCalls());
        System.out.println("   text length   : " + (output.getText() != null ? output.getText().length() : 0));
        System.out.println("   text Content   : " + output.getText());
        System.out.println("   finishReason: " + response.getResult().getMetadata().getFinishReason());
    }

    @Test
    void scenario1b_normalChat_stream() {
        System.out.println("\n════════════════════════════════════════");
        System.out.println("Scenario 1b: normal chat — .stream()");
        System.out.println("════════════════════════════════════════");

        AtomicInteger chunkCount         = new AtomicInteger(0);
        AtomicInteger reasoningChunkCount  = new AtomicInteger(0);
        AtomicInteger textChunkCount       = new AtomicInteger(0);
        AtomicReference<String> finishReason = new AtomicReference<>("N/A");
        StringBuilder fullText      = new StringBuilder();
        StringBuilder fullReasoning = new StringBuilder();

        long start = System.currentTimeMillis();

        Flux<ChatResponse> flux = chatClient.prompt()
                .messages(new UserMessage("Describe the Java language in one sentence."))
                .stream()
                .chatResponse();

        flux.doOnNext(chunk -> {
            int idx = chunkCount.incrementAndGet();
            if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
            AssistantMessage am = chunk.getResult().getOutput();

            String token = am.getText();
            if (token != null && !token.isEmpty()) {
                fullText.append(token);
                textChunkCount.incrementAndGet();
                System.out.printf("  chunk[%03d] [TEXT     ] \"%s\"%n", idx, token);
            }

            Object reasoning = am.getMetadata().get("reasoningContent");
            if (reasoning != null && !reasoning.toString().isEmpty()) {
                fullReasoning.append(reasoning);
                reasoningChunkCount.incrementAndGet();
                String preview = reasoning.toString().length() > 40
                        ? reasoning.toString().substring(0, 40) + "..."
                        : reasoning.toString();
                System.out.printf("  chunk[%03d] [REASONING] \"%s\"%n", idx, preview);
            }

            String reason = chunk.getResult().getMetadata().getFinishReason() != null
                    ? chunk.getResult().getMetadata().getFinishReason().toString()
                    : "null";
            if (!"null".equals(reason) && !"NONE".equals(reason) && !reason.isBlank()) {
                finishReason.set(reason);
                System.out.printf("  chunk[%03d] [FINISH   ] finishReason=%s%n", idx, reason);
            }
        }).blockLast(Duration.ofMinutes(2));

        long elapsed = System.currentTimeMillis() - start;

        System.out.println("\n✅ Streaming completed, total time: " + elapsed + "ms");
        System.out.println("   chunk count        : " + chunkCount.get());
        System.out.println("   reasoning content chunk count  : " + reasoningChunkCount.get());
        System.out.println("   text chunk count      : " + textChunkCount.get());
        System.out.println("   finishReason      : " + finishReason.get());
        System.out.println("   full text           : " + fullText);
        System.out.println("   reasoning content (first 200 chars): "
                + (fullReasoning.length() > 200
                   ? fullReasoning.substring(0, 200) + "..."
                   : fullReasoning));
    }

    @Test
    void scenario2a_toolCall_call() {
        System.out.println("\n════════════════════════════════════════");
        System.out.println("Scenario 2a: tool call — .call()");
        System.out.println("════════════════════════════════════════");

        long start = System.currentTimeMillis();

        ChatResponse response = chatClientWithTools.prompt()
                .messages(new UserMessage("What is the weather in Beijing today?"))
                .call()
                .chatResponse();

        long elapsed = System.currentTimeMillis() - start;
        AssistantMessage output = response.getResult().getOutput();

        System.out.println("✅ Response time: " + elapsed + "ms");
        System.out.println("   hasToolCalls: " + output.hasToolCalls());
        System.out.println("   text Content   : " + output.getText());
        System.out.println("   finishReason: " + response.getResult().getMetadata().getFinishReason());

        if (output.hasToolCalls()) {
            output.getToolCalls().forEach(tc -> {
                System.out.println("   ── tool_call ──");
                System.out.println("      id       : " + tc.id());
                System.out.println("      name     : " + tc.name());
                System.out.println("      arguments: " + tc.arguments());
            });
        }
    }

    @Test
    void scenario2b_toolCall_stream() {
        System.out.println("\n════════════════════════════════════════");
        System.out.println("Scenario 2b: tool call — .stream()  ← core exploration");
        System.out.println("════════════════════════════════════════");

        AtomicInteger chunkCount = new AtomicInteger(0);
        AtomicReference<AssistantMessage> lastToolCallChunk = new AtomicReference<>();
        StringBuilder accumulatedArgs = new StringBuilder();
        StringBuilder accumulatedText = new StringBuilder();

        long start = System.currentTimeMillis();

        Flux<ChatResponse> flux = chatClientWithTools.prompt()
                .messages(new UserMessage("What is the weather in Beijing today?"))
                .stream()
                .chatResponse();

        flux.doOnNext(chunk -> {
            int idx = chunkCount.incrementAndGet();
            if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
            AssistantMessage am = chunk.getResult().getOutput();
            String token = am.getText();
            String reason = chunk.getResult().getMetadata().getFinishReason() != null
                    ? chunk.getResult().getMetadata().getFinishReason().toString()
                    : "null";

            Object reasoning = am.getMetadata().get("reasoningContent");
            if (reasoning != null && !reasoning.toString().isEmpty()) {
                String preview = reasoning.toString().length() > 30
                        ? reasoning.toString().substring(0, 30) + "..."
                        : reasoning.toString();
                System.out.printf("  chunk[%02d] [REASONING] \"%s\"%n", idx, preview);
                return;
            }

            if (token != null && !token.isEmpty()) {
                accumulatedText.append(token);
            }

            System.out.printf("  chunk[%02d] hasToolCalls=%-5s finishReason=%-12s text=%s%n",
                    idx,
                    am.hasToolCalls(),
                    reason,
                    token == null ? "<null>" : ("\"" + token + "\""));

            if (am.hasToolCalls()) {
                lastToolCallChunk.set(am);
                am.getToolCalls().forEach(tc -> {
                    System.out.printf("             └─ toolCall: id=%s name=%s args=%s%n",
                            tc.id(), tc.name(), tc.arguments());
                    if (tc.arguments() != null) {
                        accumulatedArgs.append(tc.arguments());
                    }
                });
            }
        }).blockLast(Duration.ofMinutes(2));

        long elapsed = System.currentTimeMillis() - start;

        System.out.println("\n✅ Streaming tool call completed, total time: " + elapsed + "ms");
        System.out.println("   chunk count         : " + chunkCount.get());
        System.out.println("   accumulated text          : " + accumulatedText);
        System.out.println("   accumulated tool arguments: " + accumulatedArgs);

        AssistantMessage last = lastToolCallChunk.get();
        if (last != null) {
            System.out.println("\n📌 Last chunk with toolCalls details:");
            System.out.println("   hasToolCalls: " + last.hasToolCalls());
            last.getToolCalls().forEach(tc -> {
                System.out.println("   toolCall.id       : " + tc.id());
                System.out.println("   toolCall.name     : " + tc.name());
                System.out.println("   toolCall.arguments: " + tc.arguments());
            });
        } else {
            System.out.println("\n⚠️  no chunks contained toolCalls; this model/API does not return tool_calls in stream mode");
        }
    }

    @Test
    void scenario2c_toolCall_stream_aggregate() {
        System.out.println("\n════════════════════════════════════════");
        System.out.println("Scenario 2c: tool call — .stream() + collect aggregation");
        System.out.println("════════════════════════════════════════");

        long start = System.currentTimeMillis();

        List<ChatResponse> allChunks = chatClientWithTools.prompt()
                .messages(new UserMessage("What is the weather in Beijing today?"))
                .stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofMinutes(2));

        long elapsed = System.currentTimeMillis() - start;

        if (allChunks == null || allChunks.isEmpty()) {
            System.out.println("⚠️  no chunks received");
            return;
        }

        System.out.println("✅ aggregation completed, total time: " + elapsed + "ms, total " + allChunks.size() + " chunks");

        StringBuilder fullText = new StringBuilder();
        List<AssistantMessage.ToolCall> allToolCalls = new ArrayList<>();
        String finalFinishReason = "N/A";

        for (ChatResponse chunk : allChunks) {
            if (chunk.getResult() == null || chunk.getResult().getOutput() == null) continue;
            AssistantMessage am = chunk.getResult().getOutput();
            if (am.getText() != null) fullText.append(am.getText());
            if (am.hasToolCalls()) allToolCalls.addAll(am.getToolCalls());

            String fr = chunk.getResult().getMetadata().getFinishReason() != null
                    ? chunk.getResult().getMetadata().getFinishReason().toString() : "null";
            if (!"null".equals(fr) && !"NONE".equals(fr)) finalFinishReason = fr;
        }

        System.out.println("\n📊 Aggregation result:");
        System.out.println("   full text        : " + fullText);
        System.out.println("   hasToolCalls     : " + !allToolCalls.isEmpty());
        System.out.println("   toolCalls count   : " + allToolCalls.size());
        System.out.println("   final finishReason: " + finalFinishReason);

        allToolCalls.forEach(tc -> {
            System.out.println("   ── tool_call ──");
            System.out.println("      id       : " + tc.id());
            System.out.println("      name     : " + tc.name());
            System.out.println("      arguments: " + tc.arguments());
        });

        System.out.println("\n💡 Conclusion:");
        if (!allToolCalls.isEmpty()) {
            System.out.println("   ✅ stream mode can retrieve complete tool_calls and aggregate to match .call()");
            System.out.println("   ✅ migration is feasible: use .stream() for real-time token push, then aggregate the full AssistantMessage into state");
        } else {
            System.out.println("   ⚠️  stream mode returned empty tool_calls; this model/API does not support streaming tool calls");
        }
    }

    private ToolCallback buildWeatherToolCallback() {
        ToolDefinition toolDefinition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESC)
                .inputSchema(TOOL_SCHEMA)
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return toolDefinition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                return "{\"weather\":\"sunny, 20°C\",\"city\":\"Beijing\"}";
            }
        };
    }
}