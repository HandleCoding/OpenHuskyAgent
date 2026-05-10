package io.github.huskyagent.service.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiPromptMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiPromptMapper mapper = new OpenAiPromptMapper();

    @Test
    void mapsTextMessagesToSpringAiMessages() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model":"assistant",
                  "messages":[
                    {"role":"system","content":"Be concise."},
                    {"role":"developer","content":"Follow rules."},
                    {"role":"user","content":"Hello"},
                    {"role":"assistant","content":"Hi"},
                    {"role":"user","content":[{"type":"text","text":"How are you?"}]}
                  ]
                }
                """, OpenAiChatCompletionRequest.class);

        OpenAiPromptMapper.MappedPrompt prompt = mapper.map(request);

        assertEquals("How are you?", prompt.displayText());
        assertEquals(5, prompt.messages().size());
        assertInstanceOf(SystemMessage.class, prompt.messages().get(0));
        assertInstanceOf(SystemMessage.class, prompt.messages().get(1));
        assertInstanceOf(UserMessage.class, prompt.messages().get(2));
        assertInstanceOf(AssistantMessage.class, prompt.messages().get(3));
        assertInstanceOf(UserMessage.class, prompt.messages().get(4));
        assertEquals("Be concise.", prompt.messages().get(0).getText());
        assertEquals("Follow rules.", prompt.messages().get(1).getText());
        assertEquals("Hello", prompt.messages().get(2).getText());
        assertEquals("Hi", prompt.messages().get(3).getText());
        assertEquals("How are you?", prompt.messages().get(4).getText());
    }

    @Test
    void rejectsToolRole() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {"model":"assistant","messages":[{"role":"tool","content":"tool result","tool_call_id":"call-1"}]}
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(OpenAiProtocolException.class, () -> mapper.map(request));
        assertEquals("unsupported_message_role", error.getCode());
    }

    @Test
    void rejectsAssistantToolCalls() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {"model":"assistant","messages":[{"role":"assistant","content":"","tool_calls":[{"id":"call-1"}]}]}
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(OpenAiProtocolException.class, () -> mapper.map(request));
        assertEquals("unsupported_tool_calls", error.getCode());
    }

    @Test
    void allowsEmptyAssistantToolCalls() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {"model":"assistant","messages":[{"role":"assistant","content":"ok","tool_calls":[]}]}
                """, OpenAiChatCompletionRequest.class);

        OpenAiPromptMapper.MappedPrompt prompt = mapper.map(request);

        assertEquals("ok", prompt.displayText());
        assertInstanceOf(AssistantMessage.class, prompt.messages().get(0));
    }

    @Test
    void rejectsNonTextContentPart() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {"model":"assistant","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}]}
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(OpenAiProtocolException.class, () -> mapper.map(request));
        assertEquals("unsupported_content_type", error.getCode());
    }
}
