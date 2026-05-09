package io.github.huskyagent.domain.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentMessageQueueTest {

    @Test
    void offerAndTake() throws InterruptedException {
        SubAgentMessageQueue queue = new SubAgentMessageQueue();
        SubAgentMessage.Started msg = new SubAgentMessage.Started("sess-1", "分析项目", 0);

        queue.offer(msg);
        SubAgentMessage received = queue.take();

        assertInstanceOf(SubAgentMessage.Started.class, received);
        assertEquals("sess-1", ((SubAgentMessage.Started) received).sessionId());
        assertEquals("分析项目", ((SubAgentMessage.Started) received).goal());
    }

    @Test
    void pollWithTimeout() throws InterruptedException {
        SubAgentMessageQueue queue = new SubAgentMessageQueue();
        SubAgentMessage msg = queue.poll(100, TimeUnit.MILLISECONDS);
        assertNull(msg);
    }

    @Test
    void closeMarksQueue() {
        SubAgentMessageQueue queue = new SubAgentMessageQueue();
        assertFalse(queue.isClosed());
        queue.close();
        assertTrue(queue.isClosed());
    }

    @Test
    void drainCollectsAllMessages() {
        SubAgentMessageQueue queue = new SubAgentMessageQueue();
        queue.offer(new SubAgentMessage.Started("s1", "task1", 0));
        queue.offer(new SubAgentMessage.Progress("working...", 0));
        queue.offer(new SubAgentMessage.ToolCallStarted("read_file", "path=/foo", 0));

        List<SubAgentMessage> drained = queue.drain();
        assertEquals(3, drained.size());
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void concurrentOfferAndTake() throws Exception {
        SubAgentMessageQueue queue = new SubAgentMessageQueue();
        int messageCount = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        // Consumer thread
        Thread consumer = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < messageCount; i++) {
                    SubAgentMessage msg = queue.poll(5, TimeUnit.SECONDS);
                    if (msg == null) {
                        error.set(new AssertionError("Timeout waiting for message " + i));
                        return;
                    }
                }
            } catch (Exception e) {
                error.set(e);
            }
        });

        // Producer thread
        Thread producer = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < messageCount; i++) {
                    queue.offer(new SubAgentMessage.Progress("step " + i, 0));
                }
            } catch (Exception e) {
                error.set(e);
            }
        });

        consumer.start();
        producer.start();
        startLatch.countDown();

        consumer.join(10000);
        producer.join(5000);

        assertNull(error.get(), "No errors should occur during concurrent access");
    }

    @Test
    void allMessageTypesSupported() {
        SubAgentMessageQueue queue = new SubAgentMessageQueue();

        queue.offer(new SubAgentMessage.Started("s1", "goal", 0));
        queue.offer(new SubAgentMessage.ToolCallStarted("read_file", "path=/a", 0));
        queue.offer(new SubAgentMessage.ToolCallCompleted("read_file", 100, true, 0));
        queue.offer(new SubAgentMessage.Progress("thinking...", 0));
        queue.offer(new SubAgentMessage.Completed("s1", "done", List.of(), 5000, 100, 50, 0));
        queue.offer(new SubAgentMessage.Failed("s1", "error", List.of(), 0));
        queue.offer(new SubAgentMessage.Timeout("s1", 0));

        List<SubAgentMessage> all = queue.drain();
        assertEquals(7, all.size());
        assertInstanceOf(SubAgentMessage.Started.class, all.get(0));
        assertInstanceOf(SubAgentMessage.ToolCallStarted.class, all.get(1));
        assertInstanceOf(SubAgentMessage.ToolCallCompleted.class, all.get(2));
        assertInstanceOf(SubAgentMessage.Progress.class, all.get(3));
        assertInstanceOf(SubAgentMessage.Completed.class, all.get(4));
        assertInstanceOf(SubAgentMessage.Failed.class, all.get(5));
        assertInstanceOf(SubAgentMessage.Timeout.class, all.get(6));
    }

    @Test
    void toolTraceEntryRecord() {
        SubAgentMessage.ToolTraceEntry entry = new SubAgentMessage.ToolTraceEntry(
                "read_file", "completed", 150);
        assertEquals("read_file", entry.tool());
        assertEquals("completed", entry.status());
        assertEquals(150, entry.durationMs());
    }
}
