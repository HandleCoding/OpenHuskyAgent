package io.github.huskyagent.application.session;

import io.github.huskyagent.infra.session.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScopedRuntimeContextTest {

    private final ScopedRuntimeContext context = new ScopedRuntimeContext();

    @AfterEach
    void tearDown() {
        SessionContext.clear();
    }

    @Test
    void bindsScopeDuringCallAndClearsAfterSuccess() {
        RuntimeScope scope = RuntimeScopeTestFixtures.completeScope();

        String sessionId = context.call(scope, () -> {
            assertNotNull(SessionContext.getScope());
            assertEquals("session-1", SessionContext.get());
            assertEquals("/tmp/work", SessionContext.getScope().getWorkingDirectory());
            return SessionContext.get();
        });

        assertEquals("session-1", sessionId);
        assertNull(SessionContext.getScope());
    }

    @Test
    void clearsScopeAfterException() {
        RuntimeScope scope = RuntimeScopeTestFixtures.completeScope();

        assertThrows(IllegalArgumentException.class, () -> context.call(scope, () -> {
            assertNotNull(SessionContext.getScope());
            throw new IllegalArgumentException("boom");
        }));

        assertNull(SessionContext.getScope());
    }

    @Test
    void incompleteScopeDoesNotRunAction() {
        RuntimeScope scope = RuntimeScope.builder().sessionId("session-1").build();
        boolean[] ran = {false};

        assertThrows(IllegalStateException.class, () -> context.call(scope, () -> {
            ran[0] = true;
            return null;
        }));

        assertFalse(ran[0]);
        assertNull(SessionContext.getScope());
    }
}
