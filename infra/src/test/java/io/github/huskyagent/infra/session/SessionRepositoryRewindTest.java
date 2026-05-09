package io.github.huskyagent.infra.session;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionRepository — checkpoint_id 关联写入、读取及 rewind 截断测试。
 * 每个测试用独立的 SQLite 文件 DB（@TempDir），无需 Spring 容器。
 */
class SessionRepositoryRewindTest {

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private SessionRepository repo;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("test-" + UUID.randomUUID() + ".db").toString();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dbPath);
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(cfg);
        repo = new SessionRepository(dataSource);
        repo.createSession("s1");
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    // ── checkpoint_id 写入 / 读取 ──────────────────────────────────────────────

    @Test
    void saveMessage_withCheckpointId_persistsAndReads() {
        Long id = repo.saveMessage("s1", "user", "hello", "cp-001");

        assertNotNull(id);
        List<MessageEntity> messages = repo.findMessagesBySessionId("s1");
        assertEquals(1, messages.size());
        assertEquals("cp-001", messages.get(0).getCheckpointId());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("hello", messages.get(0).getContent());
    }

    @Test
    void saveMessage_withoutCheckpointId_storesNull() {
        Long id = repo.saveMessage("s1", "assistant", "hi");

        assertNotNull(id);
        List<MessageEntity> messages = repo.findMessagesBySessionId("s1");
        assertNull(messages.get(0).getCheckpointId());
    }

    @Test
    void saveMessage_multipleMessages_checkpointIdIndependent() {
        repo.saveMessage("s1", "user", "msg1", "cp-001");
        repo.saveMessage("s1", "assistant", "reply1", null);
        repo.saveMessage("s1", "user", "msg2", "cp-002");
        repo.saveMessage("s1", "assistant", "reply2", null);

        List<MessageEntity> messages = repo.findMessagesBySessionId("s1");
        assertEquals(4, messages.size());
        assertEquals("cp-001", messages.get(0).getCheckpointId());
        assertNull(messages.get(1).getCheckpointId());
        assertEquals("cp-002", messages.get(2).getCheckpointId());
        assertNull(messages.get(3).getCheckpointId());
    }

    // ── findUserMessagesBySessionId 包含 checkpoint_id ────────────────────────

    @Test
    void findUserMessages_returnsCheckpointId() {
        repo.saveMessage("s1", "user", "q1", "cp-aaa");
        repo.saveMessage("s1", "assistant", "a1", null);
        repo.saveMessage("s1", "user", "q2", "cp-bbb");

        List<MessageEntity> users = repo.findUserMessagesBySessionId("s1");
        assertEquals(2, users.size());
        assertEquals("cp-aaa", users.get(0).getCheckpointId());
        assertEquals("cp-bbb", users.get(1).getCheckpointId());
    }

    // ── deleteMessagesAfter 截断逻辑 ──────────────────────────────────────────

    @Test
    void deleteMessagesAfter_removesCorrectMessages() {
        Long id1 = repo.saveMessage("s1", "user", "msg1", "cp-1");
        repo.saveMessage("s1", "assistant", "reply1", null);
        Long id3 = repo.saveMessage("s1", "user", "msg2", "cp-2");
        repo.saveMessage("s1", "assistant", "reply2", null);

        // 截断 id3 之前（id >= id3 的删除）
        repo.deleteMessagesAfter("s1", id3 - 1);

        List<MessageEntity> remaining = repo.findMessagesBySessionId("s1");
        assertEquals(2, remaining.size());
        assertEquals(id1, remaining.get(0).getId());
    }

    @Test
    void deleteMessagesAfter_deletesAllWhenAfterIsZero() {
        repo.saveMessage("s1", "user", "msg1", "cp-1");
        repo.saveMessage("s1", "assistant", "reply1", null);

        repo.deleteMessagesAfter("s1", 0L);

        assertTrue(repo.findMessagesBySessionId("s1").isEmpty());
    }

    @Test
    void deactivateOtherSessionsForKey_keepsOnlyRequestedSessionActive() {
        repo.createSession("s2");
        repo.updateSessionIsolation("s1", "user", "FEISHU", "assistant", "DIRECT",
                "chat", null, "sender", "session-key");
        repo.updateSessionIsolation("s2", "user", "FEISHU", "assistant", "DIRECT",
                "chat", null, "sender", "session-key");

        repo.deactivateOtherSessionsForKey("session-key", "s2");

        SessionEntity oldSession = repo.findSession("s1").orElseThrow();
        SessionEntity activeSession = repo.findBySessionKey("session-key").orElseThrow();
        assertEquals("inactive", oldSession.getStatus());
        assertEquals("s2", activeSession.getId());
        assertEquals("active", activeSession.getStatus());
    }

    // ── migration 幂等性：重复初始化不报错 ────────────────────────────────────

    @Test
    void constructorTwice_migrationsAreIdempotent() {
        // 用同一 DataSource 再构造一次，migration 应幂等不抛异常
        assertDoesNotThrow(() -> new SessionRepository(dataSource));
    }
}
