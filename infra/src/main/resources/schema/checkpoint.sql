CREATE TABLE IF NOT EXISTS checkpoints (
    thread_id     TEXT NOT NULL,
    checkpoint_id TEXT NOT NULL,
    data          TEXT NOT NULL,
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (thread_id, checkpoint_id)
);

CREATE INDEX IF NOT EXISTS idx_checkpoints_thread ON checkpoints(thread_id);
