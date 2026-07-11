package io.github.huskyagent.infra.llm.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmProtocolTest {

    @Test
    void parsesAliases() {
        assertEquals(LlmProtocol.OPENAI_CHAT_COMPLETIONS, LlmProtocol.fromConfig(null));
        assertEquals(LlmProtocol.OPENAI_CHAT_COMPLETIONS, LlmProtocol.fromConfig("openai-compatible"));
        assertEquals(LlmProtocol.OPENAI_CHAT_COMPLETIONS, LlmProtocol.fromConfig("openai_chat_completions"));
        assertEquals(LlmProtocol.ANTHROPIC_MESSAGES, LlmProtocol.fromConfig("anthropic"));
        assertEquals(LlmProtocol.ANTHROPIC_MESSAGES, LlmProtocol.fromConfig("anthropic_messages"));
    }

    @Test
    void rejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> LlmProtocol.fromConfig("gemini"));
    }
}
