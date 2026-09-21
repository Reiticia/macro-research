CREATE TABLE analysis_feedback_notification (
    feedback_id INTEGER NOT NULL REFERENCES analysis_feedback(id) ON DELETE CASCADE,
    chat_id INTEGER NOT NULL,
    notified_at TEXT,
    PRIMARY KEY(feedback_id, chat_id)
);

CREATE INDEX idx_feedback_notification_pending
ON analysis_feedback_notification(feedback_id, notified_at);
