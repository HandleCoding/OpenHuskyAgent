package io.github.huskyagent.infra.session;

public interface CheckpointStoreProvider {
    String type();

    CheckpointStore store(SessionScope scope);
}
