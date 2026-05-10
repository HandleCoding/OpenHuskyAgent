package io.github.huskyagent.service.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiPromptMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiPromptMapper mapper = new OpenAiPromptMapper();

    @Test
    void mapsTextMessages() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {
                  "model":"assistant",
                  "messages":[
                    {"role":"system","content":"Be concise."},
                    {"role":"user","content":"Hello"}
                  ]
                }
                """, OpenAiChatCompletionRequest.class);

        String prompt = mapper.toPrompt(request);

        assertEquals("System: Be concise.\n\nUser: Hello", prompt);
    }

    @Test
    void rejectsDeveloperRole() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {"model":"assistant","messages":[{"role":"developer","content":"Follow rules"}]}
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(OpenAiProtocolException.class, () -> mapper.toPrompt(request));
        assertEquals("unsupported_message_role", error.getCode());
    }

    @Test
    void rejectsNonTextContentPart() throws Exception {
        OpenAiChatCompletionRequest request = objectMapper.readValue("""
                {"model":"assistant","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}]}
                """, OpenAiChatCompletionRequest.class);

        OpenAiProtocolException error = assertThrows(OpenAiProtocolException.class, () -> mapper.toPrompt(request));
        assertEquals("unsupported_content_type", error.getCode());
    }
}
