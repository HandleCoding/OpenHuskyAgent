package org.springframework.ai.openai;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.core.retry.RetryTemplate;

import java.util.List;

/**
 * Extends OpenAiChatModel to pass reasoning_content back in assistant history messages
 * during multi-turn conversations. Spring AI does not do this by default.
 *
 * Strategy: let the parent build the full ChatCompletionRequest (handles options merging,
 * tool definitions, streamOptions), then patch the assistant messages in-place to inject
 * the reasoningContent from AssistantMessage metadata before the request is sent.
 */
public class HuskyOpenAiChatModel extends OpenAiChatModel {

    public HuskyOpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions,
                                ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
                                ObservationRegistry observationRegistry) {
        super(openAiApi, defaultOptions, toolCallingManager, retryTemplate, observationRegistry);
    }

    @Override
    ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {
        ChatCompletionRequest request = super.createRequest(prompt, stream);
        patchReasoningContent(request.messages(), prompt.getInstructions());
        return request;
    }

    private void patchReasoningContent(
            List<ChatCompletionMessage> requestMessages,
            List<org.springframework.ai.chat.messages.Message> instructions) {

        // ASSISTANT always serializes to 1 requestMessage regardless of tool_call count.
        // ToolResponseMessage serializes to N requestMessages (one per tool response),
        // so we must advance reqIdx by the actual response count to stay aligned.
        int reqIdx = 0;
        for (org.springframework.ai.chat.messages.Message instr : instructions) {
            if (instr.getMessageType() == MessageType.ASSISTANT) {
                String reasoningContent = (String) instr.getMetadata().get("reasoningContent");
                if (reasoningContent != null && !reasoningContent.isEmpty()) {
                    requestMessages.set(reqIdx, withReasoningContent(requestMessages.get(reqIdx), reasoningContent));
                }
                reqIdx++;
            } else if (instr.getMessageType() == MessageType.TOOL) {
                reqIdx += ((org.springframework.ai.chat.messages.ToolResponseMessage) instr).getResponses().size();
            } else {
                reqIdx++;
            }
        }
    }

    private static ChatCompletionMessage withReasoningContent(ChatCompletionMessage original, String reasoningContent) {
        return new ChatCompletionMessage(
                original.rawContent(),
                original.role(),
                original.name(),
                original.toolCallId(),
                original.toolCalls(),
                original.refusal(),
                original.audioOutput(),
                original.annotations(),
                reasoningContent);
    }
}
