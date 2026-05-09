package io.github.huskyagent.infra.ai;

import io.github.huskyagent.infra.config.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AuxiliaryClient 测试
 */
@ExtendWith(MockitoExtension.class)
class AuxiliaryClientTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private AgentConfig.AuxiliaryConfig auxiliaryConfig;

    @BeforeEach
    void setUp() {
        auxiliaryConfig = new AgentConfig.AuxiliaryConfig();
        auxiliaryConfig.setModel("test-model");
    }

    @Test
    void testGenerateTitleWithNullInput() {
        AuxiliaryClient client = new AuxiliaryClient(chatModel, auxiliaryConfig);

        String title = client.generateTitle(null);
        assertEquals("New Conversation", title);

        title = client.generateTitle("");
        assertEquals("New Conversation", title);

        title = client.generateTitle("   ");
        assertEquals("New Conversation", title);
    }

    @Test
    void testSummarizeWithNullInput() {
        AuxiliaryClient client = new AuxiliaryClient(chatModel, auxiliaryConfig);

        String summary = client.summarize(null);
        assertEquals("", summary);

        summary = client.summarize("");
        assertEquals("", summary);

        summary = client.summarize("   ");
        assertEquals("", summary);
    }

    @Test
    void testTranslateWithNullInput() {
        AuxiliaryClient client = new AuxiliaryClient(chatModel, auxiliaryConfig);

        String result = client.translate(null, "Chinese");
        assertEquals("", result);

        result = client.translate("", "Chinese");
        assertEquals("", result);
    }

    @Test
    void testExtractKeyInfoWithNullInput() {
        AuxiliaryClient client = new AuxiliaryClient(chatModel, auxiliaryConfig);

        String result = client.extractKeyInfo(null, "test");
        assertEquals("", result);

        result = client.extractKeyInfo("", "test");
        assertEquals("", result);
    }

    @Test
    void testAnalyzeImageReturnsClearErrorForEmptyImage() {
        AuxiliaryClient client = new AuxiliaryClient(chatModel, auxiliaryConfig);

        String result = client.analyzeImage(new byte[0], "image/png", "describe it");
        assertTrue(result.contains("image data is empty"));
    }

    @Test
    void testAnalyzeImageReturnsClearErrorForMissingMimeType() {
        AuxiliaryClient client = new AuxiliaryClient(chatModel, auxiliaryConfig);

        String result = client.analyzeImage(new byte[]{1}, "", "describe it");
        assertTrue(result.contains("MIME type is required"));
    }

    @Test
    void testAnalyzeImageReturnsClearErrorForMissingQuestion() {
        AuxiliaryClient client = new AuxiliaryClient(chatModel, auxiliaryConfig);

        String result = client.analyzeImage(new byte[]{1}, "image/png", "");
        assertTrue(result.contains("question is required"));
    }
}