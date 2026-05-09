package io.github.huskyagent.infra.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * 本地 Checkpoint 存储 — 委托 SqliteCheckpointSaver，行为与改动前完全一致。
 *
 * <p>由 {@link LocalCheckpointStoreConfiguration} 通过 @Bean 注册，
 * @ConditionalOnMissingBean 确保远端实现可自动替换。</p>
 */
class LocalCheckpointStore extends SqliteCheckpointSaver implements CheckpointStore {

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
