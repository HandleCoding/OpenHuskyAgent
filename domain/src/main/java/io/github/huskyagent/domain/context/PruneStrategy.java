package io.github.huskyagent.domain.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface PruneStrategy {

    List<Message> prune(List<Message> messages, PruneConfig config);

    String getName();
}
