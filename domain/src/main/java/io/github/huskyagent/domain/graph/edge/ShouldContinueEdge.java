package io.github.huskyagent.domain.graph.edge;

import io.github.huskyagent.domain.graph.ReActAgentState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.Optional;

@Slf4j
public class ShouldContinueEdge {

    private static final String LABEL_CONTINUE = "continue";
    private static final String LABEL_END      = "end";

    private final int maxReactLoops;

    public ShouldContinueEdge(int maxReactLoops) {
        this.maxReactLoops = maxReactLoops;
    }

    public AsyncCommandAction<ReActAgentState> build() {
        return AsyncCommandAction.command_async((state, config) -> {
            int loopCount = state.modelCallCount();
            Optional<Message> lastOpt = state.lastMessage();
            if (lastOpt.isEmpty()) {
                log.warn("[shouldContinue] No messages -> end");
                return new Command(LABEL_END);
            }
            Message last = lastOpt.get();
            if (last instanceof AssistantMessage am && am.hasToolCalls()) {
                if (loopCount >= maxReactLoops) {
                    log.warn("[shouldContinue] ReAct loop reached limit {}/{}; forcing end", loopCount, maxReactLoops);
                    return new Command(LABEL_END);
                }
                log.info("[shouldContinue] loop={}/{}, hasToolCalls=true, toolCount={} → continue",
                        loopCount, maxReactLoops, am.getToolCalls().size());
                return new Command(LABEL_CONTINUE);
            }
            log.info("[shouldContinue] loop={}/{}, hasToolCalls=false → end", loopCount, maxReactLoops);
            return new Command(LABEL_END);
        });
    }
}
