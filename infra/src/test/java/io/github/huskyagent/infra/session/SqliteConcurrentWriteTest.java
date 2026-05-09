package io.github.huskyagent.infra.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SqliteConcurrentWriteTest {

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private SessionRepository sessionRepository;
    private SqliteCheckpointSaver checkpointSaver;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("concurrent-" + UUID.randomUUID() + ".db").toString();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dbPath + "?journal_mode=WAL");
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(2);
        dataSource = new HikariDataSource(cfg);

        sessionRepository = new SessionRepository(dataSource);
        ObjectMapper objectMapper = new SpringAIJacksonStateSerializer<>(AgentState::new).objectMapper();
        checkpointSaver = new SqliteCheckpointSaver(dataSource, objectMapper);
        checkpointSaver.initSchema();
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void concurrentSessionMessageToolAndCheckpointWritesDoNotFailOrDropRows() throws Exception {
        int sessions = 24;
        int roundsPerSession = 20;
        ExecutorService executor = Executors.newFixedThreadPool(sessions);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < sessions; i++) {
            int sessionIndex = i;
            executor.submit(() -> {
                try {
                    start.await();
                    writeSession(sessionIndex, roundsPerSession);
                } catch (Throwable error) {
                    failures.add(error);
                }
            });
        }

        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        if (!failures.isEmpty()) {
            fail(failures.size() + " concurrent SQLite writes failed: " + failures.get(0));
        }

        assertEquals(sessions, count("sessions"));
        assertEquals(sessions * roundsPerSession * 2, count("messages"));
        assertEquals(sessions * roundsPerSession, count("tool_calls"));
        assertEquals(sessions * roundsPerSession, count("checkpoints"));
    }

    private void writeSession(int sessionIndex, int rounds) throws Exception {
        String sessionId = "session-" + sessionIndex;
        sessionRepository.createSession(sessionId, "user-" + sessionIndex);
        sessionRepository.updateSessionIsolation(
                sessionId,
                "principal-" + sessionIndex,
                "TUI",
                "assistant",
                "direct",
                "key-" + sessionIndex
        );

        for (int round = 0; round < rounds; round++) {
            String checkpointId = "cp-" + sessionIndex + "-" + round;
            checkpointSaver.put(config(sessionId), checkpoint(checkpointId, round));
            Long userMessageId = sessionRepository.saveMessage(sessionId, "user", "hello " + round, checkpointId);
            Long toolCallId = sessionRepository.saveToolCall(userMessageId, "terminal", "{\"command\":\"pwd\"}");
            sessionRepository.updateToolCallResult(toolCallId, "ok", "completed");
            sessionRepository.saveMessage(sessionId, "assistant", "reply " + round, checkpointId);
            sessionRepository.updateSessionObservability(sessionId, round, round + 1, round + 2L);
        }
    }

    private RunnableConfig config(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    private Checkpoint checkpoint(String id, int round) {
        return Checkpoint.builder()
                .id(id)
                .state(Map.of("round", round))
                .nodeId("model")
                .nextNodeId("__end__")
                .build();
    }

    private int count(String table) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table)) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
