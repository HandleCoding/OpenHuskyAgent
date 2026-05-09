package io.github.huskyagent.infra.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.core.io.ClassPathResource;

import jakarta.annotation.PostConstruct;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

@Slf4j
public class SqliteCheckpointSaver extends AbstractCheckpointSaver {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteCheckpointSaver(DataSource dataSource,
                                  ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initSchema() {
        try (InputStream is = new ClassPathResource("schema/checkpoint.sql").getInputStream()) {
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                for (String s : sql.split(";")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
            }
            log.debug("checkpoint schema initialized");
        } catch (Exception e) {
            log.warn("checkpoint schema initialization failed, possibly already exists: {}", e.getMessage());
        }
    }


    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) throws Exception {
        String threadId = threadId(config);
        LinkedList<Checkpoint> result = new LinkedList<>();
        String sql = "SELECT checkpoint_id, data FROM checkpoints WHERE thread_id = ? ORDER BY rowid DESC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String checkpointId = rs.getString("checkpoint_id");
                    String data = rs.getString("data");
                    Checkpoint cp = deserialize(checkpointId, data);
                    if (cp != null) result.add(cp);
                }
            }
        }
        log.debug("[checkpoint] loadCheckpoints threadId={}, count={}", threadId, result.size());
        return result;
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config,
            LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) throws Exception {
        String threadId = threadId(config);
        String sql = "INSERT OR REPLACE INTO checkpoints(thread_id, checkpoint_id, data) VALUES(?,?,?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, threadId);
            ps.setString(2, checkpoint.getId());
            ps.setString(3, serialize(checkpoint));
            ps.executeUpdate();
        }
        log.debug("[checkpoint] inserted threadId={}, id={}", threadId, checkpoint.getId());
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config,
            LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) throws Exception {
        String threadId = threadId(config);
        String sql = "UPDATE checkpoints SET data = ? WHERE thread_id = ? AND checkpoint_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serialize(checkpoint));
            ps.setString(2, threadId);
            ps.setString(3, checkpoint.getId());
            ps.executeUpdate();
        }
        log.debug("[checkpoint] updated threadId={}, id={}", threadId, checkpoint.getId());
    }

    @Override
    protected Tag releaseCheckpoints(RunnableConfig config,
            LinkedList<Checkpoint> checkpoints) throws Exception {
        String threadId = threadId(config);
        String sql = "DELETE FROM checkpoints WHERE thread_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, threadId);
            ps.executeUpdate();
        }
        log.debug("[checkpoint] released threadId={}", threadId);
        return new Tag(threadId, checkpoints);
    }

    /**
     * Drops checkpoints newer than the rewind anchor while preserving the anchor itself.
     */
    public void deleteCheckpointsAfter(String sessionId, String checkpointId) {
        String findRowidSql = "SELECT rowid FROM checkpoints WHERE thread_id = ? AND checkpoint_id = ?";
        String deleteSql    = "DELETE FROM checkpoints WHERE thread_id = ? AND rowid > ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement findPs = conn.prepareStatement(findRowidSql)) {
            findPs.setString(1, sessionId);
            findPs.setString(2, checkpointId);
            ResultSet rs = findPs.executeQuery();
            if (!rs.next()) {
                log.warn("[rewind] checkpoint not found: session={}, checkpointId={}", sessionId, checkpointId);
                return;
            }
            long rowid = rs.getLong(1);
            try (PreparedStatement deletePs = conn.prepareStatement(deleteSql)) {
                deletePs.setString(1, sessionId);
                deletePs.setLong(2, rowid);
                int deleted = deletePs.executeUpdate();
                log.info("[rewind] deleted {} checkpoints after rowid={} in session={}", deleted, rowid, sessionId);
            }
        } catch (Exception e) {
            log.error("[rewind] Failed to delete checkpoints after checkpointId={}", checkpointId, e);
            throw new RuntimeException("Failed to delete checkpoints", e);
        }
    }


    private String serialize(Checkpoint cp) throws IOException {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("id", cp.getId());
        wrapper.put("nodeId", cp.getNodeId());
        wrapper.put("nextNodeId", cp.getNextNodeId());
        wrapper.put("state", cp.getState());
        return objectMapper.writeValueAsString(wrapper);
    }

    private Checkpoint deserialize(String checkpointId, String data) {
        try {
            Map<String, Object> wrapper = objectMapper.readValue(data, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) wrapper.get("state");
            String nodeId     = (String) wrapper.getOrDefault("nodeId", "__start__");
            String nextNodeId = (String) wrapper.getOrDefault("nextNodeId", "__end__");
            return Checkpoint.builder()
                    .id(checkpointId)
                    .state(state != null ? state : Map.of())
                    .nodeId(nodeId)
                    .nextNodeId(nextNodeId)
                    .build();
        } catch (Exception e) {
            log.warn("[checkpoint] deserialization failed id={}: {}", checkpointId, e.getMessage());
            return null;
        }
    }
}
