package io.github.huskyagent.infra.session;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class LocalCheckpointStoreProvider implements CheckpointStoreProvider {

    private final LocalCheckpointStore localCheckpointStore;

    @Override
    public String type() {
        return "local";
    }

    @Override
    public CheckpointStore store(SessionScope scope) {
        return localCheckpointStore;
    }
}
