package io.github.huskyagent.infra.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CheckpointStoreFactoryTest {

    @Test
    void returnsLocalForNullOrBlankType() {
        CheckpointStore local = mock(CheckpointStore.class);
        CheckpointStoreFactory factory = new CheckpointStoreFactory(List.of(provider("local", local)));

        assertSame(local, factory.forCheckpointType(null));
        assertSame(local, factory.forCheckpointType("  "));
    }

    @Test
    void returnsRegisteredStoreForType() {
        CheckpointStore local = mock(CheckpointStore.class);
        CheckpointStore remote = mock(CheckpointStore.class);
        CheckpointStoreFactory factory = new CheckpointStoreFactory(List.of(
                provider("local", local),
                provider("postgres", remote)));

        assertSame(remote, factory.forCheckpointType(" POSTGRES "));
    }

    @Test
    void usesSessionScopeCheckpointType() {
        CheckpointStore remote = mock(CheckpointStore.class);
        CheckpointStoreFactory factory = new CheckpointStoreFactory(List.of(
                provider("local", mock(CheckpointStore.class)),
                provider("postgres", remote)));

        assertSame(remote, factory.forSessionScope(SessionScope.builder().checkpointType("postgres").build()));
    }

    @Test
    void throwsForUnknownCheckpointType() {
        CheckpointStoreFactory factory = new CheckpointStoreFactory(List.of(provider("local", mock(CheckpointStore.class))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.forCheckpointType("redis"));
        assertTrue(error.getMessage().contains("Unsupported checkpoint store type: redis"));
    }

    @Test
    void rejectsDuplicateProviderTypes() {
        assertThrows(IllegalStateException.class, () -> new CheckpointStoreFactory(List.of(
                provider("local", mock(CheckpointStore.class)),
                provider(" LOCAL ", mock(CheckpointStore.class)))));
    }

    private CheckpointStoreProvider provider(String type, CheckpointStore store) {
        return new CheckpointStoreProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public CheckpointStore store(SessionScope scope) {
                return store;
            }
        };
    }
}
