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

/**
 * 会话管理器
 * 负责会话的创建、加载、保存和历史管理
 *
 * system prompt 由 PromptBuilder 在运行时动态注入，不持久化到数据库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionManager {

    private final SessionRepository sessionRepository;

    /**
     * 创建新会话
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessionRepository.createSession(sessionId);
        log.info("创建新会话: {}", sessionId);
        return sessionId;
    }

    /**
     * 创建新会话（指定 userId，用于 Chatbot 多用户场景）
     */
    public String createSessionForUser(String userId) {
        String sessionId = UUID.randomUUID().toString();
        sessionRepository.createSession(sessionId, userId);
        log.info("创建新会话: sessionId={}, userId={}", sessionId, userId);
        return sessionId;
    }

    /**
     * 创建新会话（指定ID）
     */
    public void createSession(String sessionId) {
        sessionRepository.createSession(sessionId);
        log.info("创建新会话: {}", sessionId);
    }

    /**
     * 获取所有会话
     */
    public List<SessionEntity> listSessions() {
        return sessionRepository.findAllSessions();
    }

    public List<SessionEntity> listSessionsByScope(String ownerPrincipalId, String channelType, String sceneId) {
        return sessionRepository.findSessionsByScope(ownerPrincipalId, channelType, sceneId);
    }

    public int countMessages(String sessionId) {
        return sessionRepository.countMessages(sessionId);
    }

    /**
     * 加载会话消息历史
     */
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

    /**
     * 保存用户消息，关联写入前最新的 checkpoint_id，供 rewind 精确定位 checkpoint 边界。
     */
    public void saveUserMessage(String sessionId, String content) {
        saveUserMessage(sessionId, content, null);
    }

    public void saveUserMessage(String sessionId, String content, String checkpointId) {
        sessionRepository.saveMessage(sessionId, "user", content, checkpointId);
        log.debug("保存用户消息: sessionId={}, checkpointId={}, content={}", sessionId, checkpointId,
            content.length() > 50 ? content.substring(0, 50) + "..." : content);
    }

    /**
     * 保存助手消息
     */
    public void saveAssistantMessage(String sessionId, String content) {
        sessionRepository.saveMessage(sessionId, "assistant", content);
        log.debug("保存助手消息: sessionId={}, content={}", sessionId,
            content.length() > 50 ? content.substring(0, 50) + "..." : content);
    }

    /**
     * 保存系统消息（仅供 PromptBuilder 外部调用使用，不在 initializeSession 中自动写入）
     */
    public void saveSystemMessage(String sessionId, String content) {
        sessionRepository.saveMessage(sessionId, "system", content);
        log.debug("保存系统消息: sessionId={}", sessionId);
    }

    /** 返回 role='user' 的消息列表，供 /rewind 展示选择 */
    public List<MessageEntity> listUserMessages(String sessionId) {
        return sessionRepository.findUserMessagesBySessionId(sessionId);
    }

    /** 返回指定 message 关联的 checkpoint_id，供 rewind 定位 checkpoint 边界 */
    public String getCheckpointIdForMessage(String sessionId, long messageId) {
        return sessionRepository.findMessagesBySessionId(sessionId).stream()
                .filter(e -> e.getId() != null && e.getId() == messageId)
                .map(io.github.huskyagent.infra.session.MessageEntity::getCheckpointId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * 回退到选中的 user message 所在轮次（含该轮的 AI 回复）。
     * 找到选中 user message 之后的下一条 user message，从那里开始删除。
     * Checkpoint 的覆写由 ReActAgentApp 用 graph.updateState + ReplaceAllWith 完成。
     *
     * @param sessionId       会话ID
     * @param selectedUserMsgId 用户选中的 user message id
     * @return 截断后剩余的完整消息列表（供 caller 构建 ReplaceAllWith）
     */
    /**
     * 截断 messages 表到选中轮次，返回截断是否实际发生（false 表示已经是最后一轮，无需操作）。
     * checkpoint 的回退由 ReActAgentApp 负责，从 graph state 取完整消息列表后覆写。
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
        if (selectedIdx == -1) throw new IllegalArgumentException("消息不存在: " + selectedUserMsgId);

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

    /**
     * 将数据库实体转换为 Spring AI Message
     */
    private Message convertToMessage(MessageEntity entity) {
        String role = entity.getRole();
        String content = entity.getContent();

        return switch (role) {
            case "user" -> new UserMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "system" -> new SystemMessage(content);
            default -> {
                log.warn("未知的消息角色: {}", role);
                yield null;
            }
        };
    }
}
