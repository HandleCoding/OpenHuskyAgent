package io.github.huskyagent.application.hook;

import io.github.huskyagent.domain.hook.*;
import io.github.huskyagent.infra.tool.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 会话白名单自动审批 Hook — 检查工具是否在当前会话的已批准列表中。
 *
 * <p>若工具在白名单中，返回 allowWith("decision", "approved")，
 * ApprovalNode 收到后跳过 interrupt 直接放行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionWhitelistApprovalHook implements BeforeHook {

    private final ApprovalService approvalService;

    @Override
    public String name() { return "session-whitelist-approval"; }

    @Override
    public Set<HookEvent> supportedEvents() { return Set.of(HookEvent.APPROVAL_BEFORE); }

    @Override
    public int order() { return 10; }

    @Override
    public HookResult before(HookContext context) {
        String toolName = context.getString(HookDataKeys.TOOL_NAME);
        String sessionId = context.sessionId();
        if (toolName == null || sessionId == null) return HookResult.allow();

        if (approvalService.isSessionAllowed(sessionId, toolName)) {
            log.debug("[hook] 工具 {} 在会话 {} 白名单中，自动审批", toolName, sessionId);
            return HookResult.allowWith(Map.of(HookDataKeys.APPROVAL_DECISION, "approved"));
        }
        return HookResult.allow();
    }
}
