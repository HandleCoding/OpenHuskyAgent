package io.github.huskyagent.domain.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface SummaryStrategy {

    String generate(List<Message> turns, SummaryConfig config);

    String update(String previousSummary, List<Message> newTurns, SummaryConfig config);

    String getName();
}
