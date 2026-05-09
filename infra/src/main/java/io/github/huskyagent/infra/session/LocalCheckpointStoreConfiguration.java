package io.github.huskyagent.infra.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

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
