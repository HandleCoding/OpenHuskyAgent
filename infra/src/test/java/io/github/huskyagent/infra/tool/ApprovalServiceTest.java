package io.github.huskyagent.infra.tool;

import io.github.huskyagent.infra.tool.approval.ApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApprovalService（会话白名单）测试
 */
class ApprovalServiceTest {

    private ApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService();
    }

    @Test
    void testAddAndCheckSessionAllowedTool() {
        assertFalse(approvalService.isSessionAllowed("s1", "terminal"));
        approvalService.addSessionAllowedTool("s1", "terminal");
        assertTrue(approvalService.isSessionAllowed("s1", "terminal"));
    }

    @Test
    void testDifferentSessionsAreIsolated() {
        approvalService.addSessionAllowedTool("s1", "terminal");
        assertFalse(approvalService.isSessionAllowed("s2", "terminal"));
    }

    @Test
    void testGetSessionAllowedTools() {
        approvalService.addSessionAllowedTool("s1", "terminal");
        approvalService.addSessionAllowedTool("s1", "execute_code");
        var tools = approvalService.getSessionAllowedTools("s1");
        assertTrue(tools.contains("terminal"));
        assertTrue(tools.contains("execute_code"));
        assertEquals(2, tools.size());
    }

    @Test
    void testGetSessionAllowedToolsReturnsEmptyWhenNone() {
        var tools = approvalService.getSessionAllowedTools("nonexistent");
        assertTrue(tools.isEmpty());
    }

    @Test
    void testClearSession() {
        approvalService.addSessionAllowedTool("s1", "terminal");
        approvalService.clearSession("s1");
        assertFalse(approvalService.isSessionAllowed("s1", "terminal"));
        assertTrue(approvalService.getSessionAllowedTools("s1").isEmpty());
    }
}