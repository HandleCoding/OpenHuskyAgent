package io.github.huskyagent.infra.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

class LocalCheckpointStore extends SqliteCheckpointSaver implements CheckpointStore {

    /** Reflects runtime config: storage may exist, but callers still need to know whether rewind is enabled. */
    private final boolean checkpointEnabled;

    LocalCheckpointStore(DataSource dataSource,
                         ObjectMapper objectMapper,
                         boolean checkpointEnabled) {
        super(dataSource, objectMapper);
        this.checkpointEnabled = checkpointEnabled;
    }

    @Override
    public boolean isPersistent() {
        return checkpointEnabled;
    }
}
