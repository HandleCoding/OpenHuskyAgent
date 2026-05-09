package io.github.huskyagent.application.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.huskyagent.domain.hook.HookContext;
import io.github.huskyagent.domain.hook.HookDataKeys;
import io.github.huskyagent.domain.hook.HookEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditHookTest {

    private AuditHook auditHook;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        auditHook = new AuditHook();
        auditLogger = (Logger) LoggerFactory.getLogger("io.github.huskyagent.audit");
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    void nameIsAudit() {
        assertEquals("audit", auditHook.name());
    }

    @Test
    void logsToolCallAfter() {
        Map<String, Object> data = new HashMap<>();
        data.put(HookDataKeys.TOOL_NAME, "read_file");
        data.put(HookDataKeys.TOOL_STATUS, "completed");
        data.put(HookDataKeys.TOOL_DURATION_MS, 120L);

        auditHook.after(new HookContext(HookEvent.TOOL_CALL_AFTER, "s1", data));

        List<ILoggingEvent> logs = listAppender.list;
        assertFalse(logs.isEmpty());
        String msg = logs.get(0).getFormattedMessage();
        assertTrue(msg.contains("event=TOOL_CALL_AFTER"));
        assertTrue(msg.contains("session=s1"));
        assertTrue(msg.contains("tool=read_file"));
        assertTrue(msg.contains("status=completed"));
        assertTrue(msg.contains("duration=120ms"));
    }

    @Test
    void logsSessionEnd() {
        Map<String, Object> data = new HashMap<>();
        data.put(HookDataKeys.SESSION_DURATION_MS, 5000L);
        data.put(HookDataKeys.SESSION_INPUT_TOKENS, 200);
        data.put(HookDataKeys.SESSION_OUTPUT_TOKENS, 100);

        auditHook.after(new HookContext(HookEvent.SESSION_END, "s1", data));

        String msg = listAppender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("event=SESSION_END"));
        assertTrue(msg.contains("duration=5000ms"));
        assertTrue(msg.contains("inputTokens=200"));
        assertTrue(msg.contains("outputTokens=100"));
    }

    @Test
    void redactsSecretsInAuditLog() {
        Map<String, Object> data = new HashMap<>();
        data.put(HookDataKeys.TOOL_NAME, "read_file");
        data.put(HookDataKeys.TOOL_STATUS, "completed");
        data.put(HookDataKeys.TOOL_ERROR, "API key " + "sk" + "-proj-abcdef1234567890xyz not found");

        auditHook.after(new HookContext(HookEvent.TOOL_CALL_AFTER, "s1", data));

        String msg = listAppender.list.get(0).getFormattedMessage();
        assertFalse(msg.contains("sk" + "-proj-abcdef1234567890xyz"));
        assertTrue(msg.contains("...")); // redacted token has masked portion
    }

    @Test
    void logsApprovalAfter() {
        Map<String, Object> data = new HashMap<>();
        data.put(HookDataKeys.APPROVAL_DECISION, "approved");
        data.put(HookDataKeys.TOOL_NAME, "terminal");

        auditHook.after(new HookContext(HookEvent.APPROVAL_AFTER, "s1", data));

        String msg = listAppender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("decision=approved"));
        assertTrue(msg.contains("tool=terminal"));
    }

    @Test
    void logsCompression() {
        Map<String, Object> data = new HashMap<>();
        data.put(HookDataKeys.COMPRESS_ORIGINAL_COUNT, 50);
        data.put(HookDataKeys.COMPRESS_RESULT_COUNT, 10);
        data.put(HookDataKeys.COMPRESS_ORIGINAL_TOKENS, 50000L);

        auditHook.after(new HookContext(HookEvent.CONTEXT_COMPRESS, "s1", data));

        String msg = listAppender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("originalTokens=50000"));
        assertTrue(msg.contains("originalMessages=50"));
        assertTrue(msg.contains("resultMessages=10"));
    }

    @Test
    void logsSessionStart() {
        auditHook.after(new HookContext(HookEvent.SESSION_START, "s1", Map.of()));

        String msg = listAppender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("event=SESSION_START"));
        assertTrue(msg.contains("session=s1"));
    }
}