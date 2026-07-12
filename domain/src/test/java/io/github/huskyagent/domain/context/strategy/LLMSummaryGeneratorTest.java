package io.github.huskyagent.domain.context.strategy;

import io.github.huskyagent.domain.context.SummaryConfig;
import io.github.huskyagent.infra.ai.AuxiliaryClient;
import io.github.huskyagent.infra.context.ContextConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LLMSummaryGeneratorTest {

    @Mock
    private ContextConfig contextConfig;

    @Mock
    private AuxiliaryClient auxiliaryClient;

    @InjectMocks
    private LLMSummaryGenerator generator;

    @Test
    void generateUsesAuxiliaryClientNotSilentSimplePath() {
        when(auxiliaryClient.completeText(any(), eq(400))).thenReturn("## Active Task\nDo stuff");

        String summary = generator.generate(
                List.of(new UserMessage("please do stuff")),
                SummaryConfig.of(400));

        assertEquals("## Active Task\nDo stuff", summary);
        verify(auxiliaryClient).completeText(any(), eq(400));
    }

    @Test
    void generateFallsBackToSimpleWhenAuxiliaryFails() {
        when(auxiliaryClient.completeText(any(), anyInt())).thenThrow(new RuntimeException("boom"));

        String summary = generator.generate(
                List.of(new UserMessage("hello world")),
                SummaryConfig.of(100));

        assertTrue(summary.contains("Conversation Summary"));
        assertTrue(summary.contains("hello world"));
    }
}
