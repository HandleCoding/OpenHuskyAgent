package io.github.huskyagent.application.agent;

@FunctionalInterface
public interface ApprovalResponder {
    void respond(boolean approved, boolean always);
}
