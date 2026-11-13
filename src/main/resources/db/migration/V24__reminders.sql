CREATE TABLE reminders (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source VARCHAR(10) NOT NULL CHECK (source IN ('MANUAL','CRAWLED','PERSONAL')),
    target_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    enabled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_for TIMESTAMPTZ,
    PRIMARY KEY(user_id, source, target_id)
);
