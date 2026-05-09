package io.github.huskyagent.domain.session;

import io.github.huskyagent.infra.session.MessageEntity;
import io.github.huskyagent.infra.session.SessionEntity;
import io.github.huskyagent.infra.session.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionManager {

    private final SessionRepository sessionRepository;

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessionRepository.createSession(sessionId);
        log.info("Created new session: {}", sessionId);
        return sessionId;
    }

    public String createSessionForUser(String userId) {
        String sessionId = UUID.randomUUID().toString();
        sessionRepository.createSession(sessionId, userId);
        log.info("Created new session: sessionId={}, userId={}", sessionId, userId);
        return sessionId;
    }

    public void createSession(String sessionId) {
        sessionRepository.createSession(sessionId);
        log.info("Created new session: {}", sessionId);
    }

    public List<SessionEntity> listSessions() {
        return sessionRepository.findAllSessions();
    }

    public List<SessionEntity> listSessionsByScope(String ownerPrincipalId, String channelType, String sceneId) {
        return sessionRepository.findSessionsByScope(ownerPrincipalId, channelType, sceneId);
    }

    public int countMessages(String sessionId) {
        return sessionRepository.countMessages(sessionId);
    }

    public List<Message> loadMessages(String sessionId) {
        List<MessageEntity> entities = sessionRepository.findMessagesBySessionId(sessionId);
        List<Message> messages = new ArrayList<>();

        for (MessageEntity entity : entities) {
            Message message = convertToMessage(entity);
            if (message != null) {
                messages.add(message);
            }
        }

        return messages;
    }

    public void saveUserMessage(String sessionId, String content) {
        saveUserMessage(sessionId, content, null);
    }

    public void saveUserMessage(String sessionId, String content, String checkpointId) {
        sessionRepository.saveMessage(sessionId, "user", content, checkpointId);
        log.debug("Saved user message: sessionId={}, checkpointId={}, content={}", sessionId, checkpointId,
            content.length() > 50 ? content.substring(0, 50) + "..." : content);
    }

    public void saveAssistantMessage(String sessionId, String content) {
        sessionRepository.saveMessage(sessionId, "assistant", content);
        log.debug("Saved assistant message: sessionId={}, content={}", sessionId,
            content.length() > 50 ? content.substring(0, 50) + "..." : content);
    }

    public void saveSystemMessage(String sessionId, String content) {
        sessionRepository.saveMessage(sessionId, "system", content);
        log.debug("Saved system message: sessionId={}", sessionId);
    }

    public List<MessageEntity> listUserMessages(String sessionId) {
        return sessionRepository.findUserMessagesBySessionId(sessionId);
    }

    public String getCheckpointIdForMessage(String sessionId, long messageId) {
        return sessionRepository.findMessagesBySessionId(sessionId).stream()
                .filter(e -> e.getId() != null && e.getId() == messageId)
                .map(io.github.huskyagent.infra.session.MessageEntity::getCheckpointId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Rewinds the conversation to the round anchored by the selected user message
     * by deleting all later persisted messages.
     */
    public boolean rewindTo(String sessionId, long selectedUserMsgId) {
        List<MessageEntity> all = sessionRepository.findMessagesBySessionId(sessionId);

        int selectedIdx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId() != null && all.get(i).getId() == selectedUserMsgId) {
                selectedIdx = i;
                break;
            }
        }
        if (selectedIdx == -1) throw new IllegalArgumentException("Message does not exist: " + selectedUserMsgId);

        Long nextUserMsgId = null;
        for (int i = selectedIdx + 1; i < all.size(); i++) {
            if ("user".equals(all.get(i).getRole())) {
                nextUserMsgId = all.get(i).getId();
                break;
            }
        }

        if (nextUserMsgId == null) {
            log.info("[rewind] session={} selected last round, nothing to delete", sessionId);
            return false;
        }

        sessionRepository.deleteMessagesAfter(sessionId, nextUserMsgId - 1);
        log.info("[rewind] session={} rewound to round of msg id={}", sessionId, selectedUserMsgId);
        return true;
    }

    private Message convertToMessage(MessageEntity entity) {
        String role = entity.getRole();
        String content = entity.getContent();

        return switch (role) {
            case "user" -> new UserMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "system" -> new SystemMessage(content);
            default -> {
                log.warn("Unknown message role: {}", role);
                yield null;
            }
        };
    }
}
