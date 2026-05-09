package io.github.huskyagent.application.session;

import io.github.huskyagent.infra.session.SessionContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class ScopedRuntimeContext {

    public <T> T call(RuntimeScope scope, Supplier<T> action) {
        scope.requireCompleteForExecution();
        SessionContext.setScope(scope.toSessionScope());
        try {
            return action.get();
        } finally {
            SessionContext.clear();
        }
    }

    public void run(RuntimeScope scope, Runnable action) {
        call(scope, () -> {
            action.run();
            return null;
        });
    }
}
