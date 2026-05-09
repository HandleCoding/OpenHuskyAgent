package io.github.huskyagent.infra.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 本地 Checkpoint 存储配置。
 *
 * <p>始终注册本地 LocalCheckpointStore 与 local provider。
 * 远端存储通过额外的 CheckpointStoreProvider 注册，由 CheckpointStoreFactory 按 type 选择。</p>
 */
@Configuration
public class LocalCheckpointStoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(LocalCheckpointStore.class)
    public LocalCheckpointStore localCheckpointStore(
            DataSource dataSource,
            @Qualifier("checkpointObjectMapper") ObjectMapper objectMapper,
            @Value("${agent.checkpoint.enabled:true}") boolean checkpointEnabled) {
        return new LocalCheckpointStore(dataSource, objectMapper, checkpointEnabled);
    }

    @Bean
    public LocalCheckpointStoreProvider localCheckpointStoreProvider(LocalCheckpointStore localCheckpointStore) {
        return new LocalCheckpointStoreProvider(localCheckpointStore);
    }
}
