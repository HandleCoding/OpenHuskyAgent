package io.github.huskyagent.domain.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 子 Agent 消息队列 — per-delegation 生命周期的线程安全队列。
 *
 * <p>子 Agent 运行期间通过 {@link #offer} 发布事件，父 Agent 通过 {@link #take}/{@link #poll} 消费。
 * 调用 {@link #close} 标记队列关闭，表示子 Agent 不再产出消息。</p>
 */
public class SubAgentMessageQueue {

    private final LinkedBlockingQueue<SubAgentMessage> queue = new LinkedBlockingQueue<>();
    private volatile boolean closed = false;

    /** 非阻塞放入消息 */
    public void offer(SubAgentMessage msg) {
        queue.offer(msg);
    }

    /** 阻塞取一条消息 */
    public SubAgentMessage take() throws InterruptedException {
        return queue.take();
    }

    /** 带超时取一条消息 */
    public SubAgentMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /** 标记队列关闭（子 Agent 运行结束） */
    public void close() {
        closed = true;
    }

    /** 队列是否已关闭 */
    public boolean isClosed() {
        return closed;
    }

    /** 排空队列中所有剩余消息（用于最终结果收集） */
    public List<SubAgentMessage> drain() {
        List<SubAgentMessage> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        return remaining;
    }
}
