package io.github.huskyagent.infra.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.zaxxer.hikari.HikariDataSource;
import io.github.huskyagent.infra.context.TokenUsage;
import org.bsc.langgraph4j.serializer.plain_text.jackson.TypeMapper;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;

@Configuration
public class SQLiteDataSourceConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.hikari.maximum-pool-size:5}")
    private int maximumPoolSize;

    @Value("${spring.datasource.hikari.minimum-idle:2}")
    private int minimumIdle;

    @Value("${spring.datasource.hikari.connection-timeout:30000}")
    private long connectionTimeout;

    @Value("${spring.datasource.hikari.idle-timeout:600000}")
    private long idleTimeout;

    @Value("${spring.datasource.hikari.max-lifetime:1800000}")
    private long maxLifetime;

    @Bean("checkpointObjectMapper")
    public ObjectMapper checkpointObjectMapper() {
        var serializer = new SpringAIJacksonStateSerializer<>(AgentState::new);

        serializer.typeMapper().register(new TypeMapper.Reference<TokenUsage>(TokenUsage.class.getName()) {});

        var module = new SimpleModule();
        module.addSerializer(TokenUsage.class, new StdSerializer<>(TokenUsage.class) {
            @Override
            public void serialize(TokenUsage value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeStartObject();
                gen.writeStringField(TypeMapper.TYPE_PROPERTY, TokenUsage.class.getName());
                gen.writeNumberField("promptTokens", value.promptTokens());
                gen.writeNumberField("completionTokens", value.completionTokens());
                gen.writeNumberField("totalTokens", value.totalTokens());
                gen.writeEndObject();
            }
        });
        module.addDeserializer(TokenUsage.class, new StdDeserializer<>(TokenUsage.class) {
            @Override
            public TokenUsage deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                JsonNode node = p.getCodec().readTree(p);
                return new TokenUsage(
                        node.path("promptTokens").asInt(0),
                        node.path("completionTokens").asInt(0),
                        node.path("totalTokens").asInt(0));
            }
        });
        serializer.objectMapper().registerModule(module);

        return serializer.objectMapper();
    }

    @Bean
    public DataSource dataSource() {
        String path = jdbcUrl.replace("jdbc:sqlite:", "").split("\\?")[0];
        File dataDir = new File(path).getParentFile();
        if (dataDir != null && !dataDir.exists()) {
            dataDir.mkdirs();
        }

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setPoolName("sqlite-hikari");
        ds.setMaximumPoolSize(maximumPoolSize);
        ds.setMinimumIdle(minimumIdle);
        ds.setConnectionTimeout(connectionTimeout);
        ds.setIdleTimeout(idleTimeout);
        ds.setMaxLifetime(maxLifetime);
        return ds;
    }
}