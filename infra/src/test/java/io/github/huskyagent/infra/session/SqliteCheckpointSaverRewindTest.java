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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqliteCheckpointSaver.deleteCheckpointsAfter — 按 checkpoint_id 精确删除后续记录。
 */
class SqliteCheckpointSaverRewindTest {

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private SqliteCheckpointSaver saver;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("test-" + UUID.randomUUID() + ".db").toString();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dbPath);
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(cfg);

        ObjectMapper om = new SpringAIJacksonStateSerializer<>(AgentState::new).objectMapper();
        saver = new SqliteCheckpointSaver(dataSource, om);
        saver.initSchema();
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    private RunnableConfig config(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    private Checkpoint checkpoint(String id) {
        return Checkpoint.builder()
                .id(id)
                .state(Map.of())
                .nodeId("model")
                .nextNodeId("__end__")
                .build();
    }

    private void insertCheckpoint(String threadId, String checkpointId) throws Exception {
        saver.put(config(threadId), checkpoint(checkpointId));
    }

    private int countCheckpoints(String threadId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM checkpoints WHERE thread_id = ?")) {
            ps.setString(1, threadId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ── deleteCheckpointsAfter 核心逻辑 ───────────────────────────────────────

    @Test
    void deleteCheckpointsAfter_deletesOnlySubsequentCheckpoints() throws Exception {
        insertCheckpoint("sess", "cp-1");
        insertCheckpoint("sess", "cp-2");
        insertCheckpoint("sess", "cp-3");
        assertEquals(3, countCheckpoints("sess"));

        saver.deleteCheckpointsAfter("sess", "cp-1");

        // cp-1 保留，cp-2 / cp-3 删除
        assertEquals(1, countCheckpoints("sess"));
        Collection<Checkpoint> remaining = saver.list(config("sess"));
        assertEquals(1, remaining.size());
        assertEquals("cp-1", remaining.iterator().next().getId());
    }

    @Test
    void deleteCheckpointsAfter_keepTargetAndAllBefore() throws Exception {
        insertCheckpoint("sess", "cp-1");
        insertCheckpoint("sess", "cp-2");
        insertCheckpoint("sess", "cp-3");
        insertCheckpoint("sess", "cp-4");

        saver.deleteCheckpointsAfter("sess", "cp-2");

        assertEquals(2, countCheckpoints("sess"));
        List<String> ids = saver.list(config("sess")).stream()
                .map(Checkpoint::getId).toList();
        assertTrue(ids.contains("cp-1"));
        assertTrue(ids.contains("cp-2"));
        assertFalse(ids.contains("cp-3"));
        assertFalse(ids.contains("cp-4"));
    }

    @Test
    void deleteCheckpointsAfter_onlyAffectsTargetSession() throws Exception {
        insertCheckpoint("sess-A", "cp-1");
        insertCheckpoint("sess-A", "cp-2");
        insertCheckpoint("sess-B", "cp-1");
        insertCheckpoint("sess-B", "cp-2");

        saver.deleteCheckpointsAfter("sess-A", "cp-1");

        assertEquals(1, countCheckpoints("sess-A"));
        assertEquals(2, countCheckpoints("sess-B")); // sess-B 不受影响
    }

    @Test
    void deleteCheckpointsAfter_lastCheckpoint_deletesNothing() throws Exception {
        insertCheckpoint("sess", "cp-1");
        insertCheckpoint("sess", "cp-2");

        // cp-2 是最新的，删它之后没有更新的，数量不变
        saver.deleteCheckpointsAfter("sess", "cp-2");

        assertEquals(2, countCheckpoints("sess"));
    }

    @Test
    void deleteCheckpointsAfter_unknownCheckpointId_doesNotThrow() {
        assertDoesNotThrow(() -> saver.deleteCheckpointsAfter("sess", "nonexistent-cp"));
    }

    @Test
    void deleteCheckpointsAfter_emptySession_doesNotThrow() {
        assertDoesNotThrow(() -> saver.deleteCheckpointsAfter("no-such-session", "cp-1"));
    }

    // ── get() 返回最新 checkpoint ─────────────────────────────────────────────

    @Test
    void get_afterDelete_returnsCorrectLatest() throws Exception {
        insertCheckpoint("sess", "cp-1");
        insertCheckpoint("sess", "cp-2");
        insertCheckpoint("sess", "cp-3");

        saver.deleteCheckpointsAfter("sess", "cp-1");

        var latest = saver.get(config("sess"));
        assertTrue(latest.isPresent());
        assertEquals("cp-1", latest.get().getId());
    }
}
