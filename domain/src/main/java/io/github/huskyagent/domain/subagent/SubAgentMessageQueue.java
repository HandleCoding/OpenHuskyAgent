package io.github.huskyagent.domain.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class SubAgentMessageQueue {

    private final LinkedBlockingQueue<SubAgentMessage> queue = new LinkedBlockingQueue<>();
    private volatile boolean closed = false;

    public void offer(SubAgentMessage msg) {
        queue.offer(msg);
    }

    public SubAgentMessage take() throws InterruptedException {
        return queue.take();
    }

    public SubAgentMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public List<SubAgentMessage> drain() {
        List<SubAgentMessage> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        return remaining;
    }
}
