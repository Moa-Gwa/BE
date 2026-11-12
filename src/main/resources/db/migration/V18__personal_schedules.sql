CREATE TABLE personal_schedules (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(100) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    location VARCHAR(200),
    memo VARCHAR(1000)
);
CREATE INDEX idx_personal_schedules_user_date ON personal_schedules(user_id, starts_at);
