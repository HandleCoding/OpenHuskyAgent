package io.github.huskyagent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@Tag("live-api")
class SpringAiChatModelCompatibilityTest extends AbstractIntegrationTest {

    private static final Properties MAIN_APP_PROPERTIES = loadMainApplicationPropertiesForTests();

    @Autowired
    private ChatModel chatModel;

    @DynamicPropertySource
    static void overrideOpenAiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> requireMainProperty("spring.ai.openai.api-key"));
        registry.add("spring.ai.openai.base-url", () -> requireMainProperty("spring.ai.openai.base-url"));
        registry.add("spring.ai.openai.chat.options.model", () -> requireMainProperty("spring.ai.openai.chat.options.model"));
        registry.add("spring.ai.openai.chat.options.temperature",
                () -> MAIN_APP_PROPERTIES.getProperty("spring.ai.openai.chat.options.temperature", "0.7"));
    }

    @Test
    void plainStreamChatWorksAgainstConfiguredUrl() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        List<ChatResponse> chunks = chatClient.prompt()
                .messages(new UserMessage("reply only with ok"))
                .stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofMinutes(2));

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());

        StringBuilder fullText = new StringBuilder();
        for (ChatResponse chunk : chunks) {
            if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                continue;
            }
            String text = chunk.getResult().getOutput().getText();
            if (text != null) {
                fullText.append(text);
            }
        }

        System.out.println("plain response: " + fullText);
        assertFalse(fullText.toString().isBlank());
    }

    @Test
    void toolCallingStreamWorksAgainstConfiguredUrl() {
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultOptions(ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build())
                .defaultToolCallbacks(buildWeatherTool())
                .build();

        List<ChatResponse> chunks = chatClient.prompt()
                .messages(new UserMessage("Call get_weather to query Beijing weather. Do not answer directly."))
                .stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofMinutes(2));

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());

        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (ChatResponse chunk : chunks) {
            if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                continue;
            }
            AssistantMessage output = chunk.getResult().getOutput();
            if (output.getText() != null) {
                text.append(output.getText());
            }
            if (output.hasToolCalls()) {
                toolCalls.addAll(output.getToolCalls());
            }
        }

        System.out.println("tool response text: " + text);
        System.out.println("tool calls: " + toolCalls);
        assertFalse(toolCalls.isEmpty(), "Expected Spring AI to receive tool_calls from the configured endpoint");
    }

    private ToolCallback buildWeatherTool() {
        ToolDefinition toolDefinition = ToolDefinition.builder()
                .name("get_weather")
                .description("Get weather information for the specified city")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "city": { "type": "string", "description": "city name" }
                          },
                          "required": ["city"]
                        }
                        """)
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

    static Properties loadMainApplicationPropertiesForTests() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties properties = factory.getObject();
        if (properties == null) {
            throw new IllegalStateException("Failed to load main application.yml");
        }
        return properties;
    }

    private static String requireMainProperty(String key) {
        String value = MAIN_APP_PROPERTIES.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing property in main application.yml: " + key);
        }
        return value;
    }
}
