-- 기존 24시간 알림 테이블·키를 유지해 이전 서버와 함께 동작할 수 있도록 추가만 한다.
CREATE TABLE event_start_reminders (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source VARCHAR(10) NOT NULL CHECK (source IN ('MANUAL','CRAWLED')),
    target_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    enabled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_for TIMESTAMPTZ,
    PRIMARY KEY(user_id, source, target_id)
);
