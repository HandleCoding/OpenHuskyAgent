package io.github.huskyagent.infra.tool.approval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级工具白名单服务
 *
 * <p>职责：维护用户选择"本次会话总是允许"后的工具白名单，
 * 避免同一会话内对同一工具重复弹出审批。</p>
 *
 * <p>危险检测逻辑已内聚到各工具自身的 {@code approvalChecker}（见 {@link ApprovalRequest}），
 * 本服务不再承担检测职责。</p>
 */
@Slf4j
@Component
public class ApprovalService {

    /** sessionId → 已授权工具名集合 */
    private final Map<String, Set<String>> sessionAllowedTools = new ConcurrentHashMap<>();

    /**
     * 将工具加入指定会话的白名单（用户选择 always 时调用）
     */
    public void addSessionAllowedTool(String sessionId, String toolName) {
        sessionAllowedTools
                .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                .add(toolName);
        log.info("工具 {} 已加入会话 {} 白名单", toolName, sessionId);
    }

    /**
     * 检查工具是否在指定会话的白名单中
     */
    public boolean isSessionAllowed(String sessionId, String toolName) {
        Set<String> allowed = sessionAllowedTools.get(sessionId);
        return allowed != null && allowed.contains(toolName);
    }

    /**
     * 获取指定会话的全部白名单工具（不可变副本）
     *
     * <p>在每次 resume 时，调用方将此 Set 同步到图状态的
     * {@code SESSION_ALLOWED_TOOLS} 通道，供 {@code ApprovalNode} 检查。</p>
     */
    public Set<String> getSessionAllowedTools(String sessionId) {
        Set<String> allowed = sessionAllowedTools.get(sessionId);
        return allowed != null ? Set.copyOf(allowed) : Set.of();
    }

    /**
     * 清除指定会话的白名单（会话结束时调用）
     */
    public void clearSession(String sessionId) {
        sessionAllowedTools.remove(sessionId);
        log.debug("已清除会话 {} 的工具白名单", sessionId);
    }
}