package io.github.huskyagent.domain.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import io.github.huskyagent.domain.hook.HookRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.InterruptableAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Slf4j
@RequiredArgsConstructor
class UserInterruptNode implements AsyncNodeActionWithConfig<ReActAgentState>, InterruptableAction<ReActAgentState> {

    static final String TYPE_KEY = "type";
    static final String QUESTION_KEY = "question";
    static final String OPTIONS_KEY = "options";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String interruptType;
    private final String resultChannel;
    private final HookRegistry hookRegistry;

    @Override
    public CompletableFuture<Map<String, Object>> apply(ReActAgentState state, RunnableConfig config) {
        String sessionId = config != null ? config.threadId().orElse(null) : null;
        Optional<String> answer = state.<String>value(resultChannel).filter(s -> !s.isEmpty());
        answer.ifPresent(value -> hookRegistry.fireAfter(HookEvent.CLARIFY_AFTER, sessionId,
                Map.of(HookDataKeys.CLARIFY_TYPE, interruptType,
                       HookDataKeys.CLARIFY_ANSWER, value)));
        return completedFuture(Map.of());
    }

    @Override
    public Optional<InterruptionMetadata<ReActAgentState>> interrupt(
            String nodeId, ReActAgentState state, RunnableConfig config) {
        if (state.<String>value(resultChannel).filter(s -> !s.isEmpty()).isPresent()) {
            log.debug("[{}] {} result present, not interrupting", nodeId, interruptType);
            return Optional.empty();
        }

        List<AssistantMessage.ToolCall> requests = state.toolExecutionRequests();
        if (requests.isEmpty()) {
            log.warn("[{}] {} requested but toolExecutionRequests is empty", nodeId, interruptType);
            return Optional.empty();
        }

        Map<String, Object> args = parseArgs(requests.get(0).arguments());
        String question = asString(args.get("question"));
        List<String> options = asStringList(args.get("options"));

        String sessionId = config != null ? config.threadId().orElse(null) : null;
        hookRegistry.fireBefore(HookEvent.CLARIFY_BEFORE, sessionId,
                Map.of(HookDataKeys.CLARIFY_TYPE, interruptType,
                       HookDataKeys.CLARIFY_QUESTION, question));

        log.info("[{}] {} interrupt: {}", nodeId, interruptType, question);
        return Optional.of(InterruptionMetadata.builder(nodeId, state)
                .putMetadata(TYPE_KEY, interruptType)
                .putMetadata(QUESTION_KEY, question)
                .putMetadata(OPTIONS_KEY, options)
                .build());
    }

    private Map<String, Object> parseArgs(String argsJson) {
        try {
            return OBJECT_MAPPER.readValue(argsJson, MAP_TYPE);
        } catch (Exception e) {
            log.warn("[{}] parse args failed: {}", interruptType, e.getMessage());
            return Map.of();
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).limit(4).toList();
        }
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }
}
