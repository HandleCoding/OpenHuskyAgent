package io.github.huskyagent.application.hook;

import io.github.huskyagent.domain.hook.*;
import io.github.huskyagent.infra.tool.approval.ApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SessionWhitelistApprovalHookTest {

    private ApprovalService approvalService;
    private SessionWhitelistApprovalHook hook;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService();
        hook = new SessionWhitelistApprovalHook(approvalService);
    }

    @Test
    void nameAndEvents() {
        assertEquals("session-whitelist-approval", hook.name());
        assertEquals(Set.of(HookEvent.APPROVAL_BEFORE), hook.supportedEvents());
        assertEquals(10, hook.order());
    }

    @Test
    void toolInWhitelist_autoApproves() {
        approvalService.addSessionAllowedTool("s1", "terminal");
        HookContext ctx = new HookContext(HookEvent.APPROVAL_BEFORE, "s1",
                Map.of(HookDataKeys.TOOL_NAME, "terminal"));

        HookResult result = hook.before(ctx);
        assertTrue(result.allowed());
        assertEquals("approved", result.getModification(HookDataKeys.APPROVAL_DECISION, String.class));
    }

    @Test
    void toolNotInWhitelist_allowsWithoutDecision() {
        HookContext ctx = new HookContext(HookEvent.APPROVAL_BEFORE, "s1",
                Map.of(HookDataKeys.TOOL_NAME, "terminal"));

        HookResult result = hook.before(ctx);
        assertTrue(result.allowed());
        assertNull(result.getModification(HookDataKeys.APPROVAL_DECISION, String.class));
    }

    @Test
    void nullToolName_allows() {
        HookContext ctx = new HookContext(HookEvent.APPROVAL_BEFORE, "s1", Map.of());
        HookResult result = hook.before(ctx);
        assertTrue(result.allowed());
    }

    @Test
    void differentSession_doesNotAutoApprove() {
        approvalService.addSessionAllowedTool("s1", "terminal");
        HookContext ctx = new HookContext(HookEvent.APPROVAL_BEFORE, "s2",
                Map.of(HookDataKeys.TOOL_NAME, "terminal"));

        HookResult result = hook.before(ctx);
        assertTrue(result.allowed());
        assertNull(result.getModification(HookDataKeys.APPROVAL_DECISION, String.class));
    }
}
