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

    public OutboundMessage execute(ChannelCommand command, InboundMessage inbound, String sceneId) {
        String name = command.name();
        return switch (name) {
            case "new", "newsession", "新会话" -> newSession(inbound, sceneId);
            case "session", "会话" -> currentSession(inbound, sceneId);
            case "help", "帮助" -> help(inbound);
            default -> unknown(command, inbound);
        };
    }

    public boolean supports(ChannelCommand command) {
        return switch (command.name()) {
            case "new", "newsession", "新会话", "session", "会话", "help", "帮助" -> true;
            default -> false;
        };
    }

    private OutboundMessage newSession(InboundMessage inbound, String sceneId) {
        RuntimeScope scope = sessionResolver.createSession(
                inbound.getPrincipal(),
                inbound.getChannelIdentity(),
                sceneId
        );
        return reply(inbound, scope.getSessionId(), "已创建新会话：" + scope.getSessionId());
    }

    private OutboundMessage currentSession(InboundMessage inbound, String sceneId) {
        Optional<String> sessionId = sessionResolver.findActiveSessionId(
                inbound.getPrincipal(),
                inbound.getChannelIdentity(),
                sceneId
        );
        String text = sessionId
                .map(id -> "当前会话：" + id)
                .orElse("当前入口还没有 active 会话，发送普通消息或 /new 会创建一个。");
        return reply(inbound, sessionId.orElse(null), text);
    }

    private OutboundMessage help(InboundMessage inbound) {
        return reply(inbound, null, "可用命令：\n/new 新建会话\n/session 查看当前会话\n/help 查看帮助");
    }

    private OutboundMessage unknown(ChannelCommand command, InboundMessage inbound) {
        return reply(inbound, null, "未知命令：/" + command.name() + "\n发送 /help 查看可用命令。");
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
