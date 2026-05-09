package io.github.huskyagent.application.hook;

import io.github.huskyagent.domain.hook.*;
import io.github.huskyagent.infra.tool.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

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
            log.debug("[hook] tool {} is allowlisted in session {}; auto-approving", toolName, sessionId);
            return HookResult.allowWith(Map.of(HookDataKeys.APPROVAL_DECISION, "approved"));
        }
        return HookResult.allow();
    }
}
