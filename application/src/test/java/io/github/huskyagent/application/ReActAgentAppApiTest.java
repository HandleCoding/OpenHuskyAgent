package io.github.huskyagent.application;

import io.github.huskyagent.application.runtime.AgentRuntimeExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActAgentAppApiTest {

    @Test
    void exposesOnlyNarrowExecutionContract() {
        assertTrue(AgentRuntimeExecutor.class.isAssignableFrom(ReActAgentApp.class));

        Method[] publicMethods = ReActAgentApp.class.getDeclaredMethods();
        assertFalse(hasPublicMethodNamed(publicMethods, "chat"));
        assertFalse(hasPublicMethodNamed(publicMethods, "chatWithApproval"));
        assertFalse(hasPublicMethodNamed(publicMethods, "chatWithInterrupts"));
        assertEquals(1, Arrays.stream(publicMethods)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals("execute"))
                .count());
    }

    private boolean hasPublicMethodNamed(Method[] methods, String name) {
        return Arrays.stream(methods)
                .anyMatch(method -> Modifier.isPublic(method.getModifiers()) && method.getName().equals(name));
    }
}
