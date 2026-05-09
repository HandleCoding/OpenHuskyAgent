package io.github.huskyagent.infra.session;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

/**
 * Checkpoint 持久化抽象。
 *
 * <p>extends LangGraph4j 的 BaseCheckpointSaver，保持与 CompileConfig.checkpointSaver() 兼容，
 * 同时增加 Husky 特有的 rewind 操作。</p>
 *
 * <p>默认 impl（LocalCheckpointStore）委托 SqliteCheckpointSaver，行为与改动前完全一致。
 * 远端 impl 可替换为 Redis/PG 等共享存储，通过 @ConditionalOnMissingBean 自动生效。</p>
 */
public interface CheckpointStore extends BaseCheckpointSaver {

    /**
     * 保留指定 checkpoint（含），删除同一 session 中 rowid 更大的所有后续 checkpoint。
     * 用于 /rewind：找到 user message 对应的 checkpoint_id，删掉它之后写入的所有 checkpoint。
     */
    void deleteCheckpointsAfter(String sessionId, String checkpointId);

    /**
     * 此 store 是否持久化存储（进程重启后数据仍在）。
     * 控制是否使用此 store 作为 graph checkpointSaver；
     * 返回 false 时 AgentGraph 会改用 MemorySaver（与 checkpointEnabled=false 行为一致）。
     */
    default boolean isPersistent() {
        return true;
    }
}
