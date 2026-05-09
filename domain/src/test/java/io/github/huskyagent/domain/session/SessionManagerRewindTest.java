package io.github.huskyagent.domain.session;

import io.github.huskyagent.infra.session.MessageEntity;
import io.github.huskyagent.infra.session.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SessionManager rewind 相关方法的单元测试。
 * 使用 Mockito mock SessionRepository，不依赖真实 DB。
 */
class SessionManagerRewindTest {

    private SessionRepository repo;
    private SessionManager manager;

    @BeforeEach
    void setUp() {
        repo = mock(SessionRepository.class);
        manager = new SessionManager(repo);
    }

    // ── getCheckpointIdForMessage ─────────────────────────────────────────────

    @Test
    void getCheckpointIdForMessage_returnsCorrectCheckpointId() {
        MessageEntity m1 = entity(10L, "user", "hello", "cp-001");
        MessageEntity m2 = entity(11L, "assistant", "hi", null);
        MessageEntity m3 = entity(12L, "user", "what?", "cp-002");
        when(repo.findMessagesBySessionId("s1")).thenReturn(List.of(m1, m2, m3));

        assertEquals("cp-001", manager.getCheckpointIdForMessage("s1", 10L));
        assertEquals("cp-002", manager.getCheckpointIdForMessage("s1", 12L));
    }

    @Test
    void getCheckpointIdForMessage_returnsNullWhenNotFound() {
        when(repo.findMessagesBySessionId("s1")).thenReturn(List.of());

        assertNull(manager.getCheckpointIdForMessage("s1", 99L));
    }

    @Test
    void getCheckpointIdForMessage_returnsNullWhenCheckpointNotRecorded() {
        MessageEntity m = entity(5L, "user", "hey", null);
        when(repo.findMessagesBySessionId("s1")).thenReturn(List.of(m));

        assertNull(manager.getCheckpointIdForMessage("s1", 5L));
    }

    // ── rewindTo — 正常截断 ───────────────────────────────────────────────────

    @Test
    void rewindTo_deletesMessagesFromNextUserRound() {
        // 4 轮：user1(id=1), assistant(id=2), user2(id=3), assistant(id=4)
        MessageEntity u1 = entity(1L, "user",      "q1", "cp-1");
        MessageEntity a1 = entity(2L, "assistant", "a1", null);
        MessageEntity u2 = entity(3L, "user",      "q2", "cp-2");
        MessageEntity a2 = entity(4L, "assistant", "a2", null);
        when(repo.findMessagesBySessionId("s1")).thenReturn(List.of(u1, a1, u2, a2));

        // rewind 到第一轮（u1），期望从 u2（id=3）开始删除，即 deleteMessagesAfter(s1, 2)
        boolean truncated = manager.rewindTo("s1", 1L);

        assertTrue(truncated);
        verify(repo).deleteMessagesAfter("s1", 2L); // nextUserMsgId - 1 = 3 - 1 = 2
    }

    @Test
    void rewindTo_selectsLastRound_returnsFalseAndNoDelete() {
        MessageEntity u1 = entity(1L, "user",      "q1", "cp-1");
        MessageEntity a1 = entity(2L, "assistant", "a1", null);
        MessageEntity u2 = entity(3L, "user",      "q2", "cp-2");
        MessageEntity a2 = entity(4L, "assistant", "a2", null);
        when(repo.findMessagesBySessionId("s1")).thenReturn(List.of(u1, a1, u2, a2));

        // 选最后一轮 u2，没有后续 user，不删
        boolean truncated = manager.rewindTo("s1", 3L);

        assertFalse(truncated);
        verify(repo, never()).deleteMessagesAfter(anyString(), anyLong());
    }

    @Test
    void rewindTo_nonexistentMessageId_throwsException() {
        when(repo.findMessagesBySessionId("s1")).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> manager.rewindTo("s1", 999L));
    }

    @Test
    void rewindTo_threeRounds_rewindToMiddle() {
        MessageEntity u1 = entity(1L, "user",      "q1", "cp-1");
        MessageEntity a1 = entity(2L, "assistant", "a1", null);
        MessageEntity u2 = entity(3L, "user",      "q2", "cp-2");
        MessageEntity a2 = entity(4L, "assistant", "a2", null);
        MessageEntity u3 = entity(5L, "user",      "q3", "cp-3");
        MessageEntity a3 = entity(6L, "assistant", "a3", null);
        when(repo.findMessagesBySessionId("s1")).thenReturn(List.of(u1, a1, u2, a2, u3, a3));

        // rewind 到第二轮 u2，期望从 u3（id=5）开始删，即 deleteMessagesAfter(s1, 4)
        boolean truncated = manager.rewindTo("s1", 3L);

        assertTrue(truncated);
        verify(repo).deleteMessagesAfter("s1", 4L);
    }

    // ── saveUserMessage 参数透传 ──────────────────────────────────────────────

    @Test
    void saveUserMessage_passesCheckpointIdToRepository() {
        manager.saveUserMessage("s1", "hello world", "cp-xyz");

        verify(repo).saveMessage("s1", "user", "hello world", "cp-xyz");
    }

    @Test
    void saveUserMessage_nullCheckpointId_passesNullToRepository() {
        manager.saveUserMessage("s1", "text", null);

        verify(repo).saveMessage("s1", "user", "text", null);
    }

    // ── listUserMessages 包含 checkpoint_id ──────────────────────────────────

    @Test
    void listUserMessages_returnsEntitiesWithCheckpointId() {
        MessageEntity u1 = entity(1L, "user", "q1", "cp-1");
        MessageEntity u2 = entity(3L, "user", "q2", "cp-2");
        when(repo.findUserMessagesBySessionId("s1")).thenReturn(List.of(u1, u2));

        List<MessageEntity> result = manager.listUserMessages("s1");

        assertEquals(2, result.size());
        assertEquals("cp-1", result.get(0).getCheckpointId());
        assertEquals("cp-2", result.get(1).getCheckpointId());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MessageEntity entity(Long id, String role, String content, String checkpointId) {
        MessageEntity e = new MessageEntity();
        e.setId(id);
        e.setRole(role);
        e.setContent(content);
        e.setCheckpointId(checkpointId);
        return e;
    }
}
