package io.github.huskyagent.infra.session;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

public interface CheckpointStore extends BaseCheckpointSaver {

    void deleteCheckpointsAfter(String sessionId, String checkpointId);

    default boolean isPersistent() {
        return true;
    }
}
