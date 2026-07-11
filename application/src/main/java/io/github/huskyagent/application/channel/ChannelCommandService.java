package io.github.huskyagent.application.channel;

import io.github.huskyagent.application.session.RuntimeScope;
import io.github.huskyagent.application.session.SessionResolver;
import io.github.huskyagent.infra.channel.InboundMessage;
import io.github.huskyagent.infra.channel.OutboundMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChannelCommandService {

    private final SessionResolver sessionResolver;

    public OutboundMessage execute(ChannelCommand command, InboundMessage inbound, String agentId) {
        String name = command.name();
        return switch (name) {
            case "new", "newsession", "new-session" -> newSession(inbound, agentId);
            case "stop" -> reply(inbound, null, "Stop is handled by the runtime bypass path.");
            case "session", "session-info" -> currentSession(inbound, agentId);
            case "help" -> help(inbound);
            default -> unknown(command, inbound);
        };
    }

    public boolean supports(ChannelCommand command) {
        return switch (command.name()) {
            case "new", "newsession", "new-session", "stop", "session", "session-info", "help" -> true;
            default -> false;
        };
    }

    private OutboundMessage newSession(InboundMessage inbound, String agentId) {
        RuntimeScope scope = sessionResolver.createSession(
                inbound.getPrincipal(),
                inbound.getChannelIdentity(),
                agentId
        );
        return reply(inbound, scope.getSessionId(), "Created new session: " + scope.getSessionId());
    }

    private OutboundMessage currentSession(InboundMessage inbound, String agentId) {
        Optional<String> sessionId = sessionResolver.findActiveSessionId(
                inbound.getPrincipal(),
                inbound.getChannelIdentity(),
                agentId
        );
        String text = sessionId
                .map(id -> "Current session: " + id)
                .orElse("This entry has no active session yet. Send a normal message or /new to create one.");
        return reply(inbound, sessionId.orElse(null), text);
    }

    private OutboundMessage help(InboundMessage inbound) {
        return reply(inbound, null, "Available commands:\n/new Create a new session\n/stop Stop current run\n/session Show current session\n/help Show help");
    }

    private OutboundMessage unknown(ChannelCommand command, InboundMessage inbound) {
        return reply(inbound, null, "Unknown command: /" + command.name() + "\nSend /help to view available commands.");
    }

    private OutboundMessage reply(InboundMessage inbound, String sessionId, String text) {
        return OutboundMessage.builder()
                .kind(OutboundMessage.Kind.TEXT)
                .sessionId(sessionId)
                .channelIdentity(inbound.getChannelIdentity())
                .replyTarget(inbound.getReplyTarget())
                .text(text)
                .build();
    }
}
