package io.github.huskyagent.application.channel;

import org.springframework.stereotype.Component;

@Component
public class BypassCommandPolicy {

    public CommandExecutionMode modeFor(ChannelCommand command) {
        if (command == null) {
            return CommandExecutionMode.NORMAL_QUEUED;
        }
        return switch (command.name()) {
            case "stop" -> CommandExecutionMode.BYPASS_CANCEL_ACTIVE;
            case "new", "newsession", "new-session" -> CommandExecutionMode.BYPASS_REPLACE_ACTIVE_AND_PENDING;
            default -> CommandExecutionMode.NORMAL_QUEUED;
        };
    }
}
