package io.github.huskyagent.domain.graph.edge;

import io.github.huskyagent.domain.graph.ReActAgentState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.Optional;

/**
 * shouldContinue 边：model 节点后判断是否继续 ReAct loop。
 * 最后消息有 tool_calls 且未超限 → continue，否则 → end。
 */
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
                log.warn("[shouldContinue] 无消息 → end");
                return new Command(LABEL_END);
            }
            Message last = lastOpt.get();
            if (last instanceof AssistantMessage am && am.hasToolCalls()) {
                if (loopCount >= maxReactLoops) {
                    log.warn("[shouldContinue] ReAct loop 已达上限 {}/{}，强制结束", loopCount, maxReactLoops);
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
