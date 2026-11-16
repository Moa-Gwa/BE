ALTER TABLE personal_schedules ADD COLUMN ends_at TIMESTAMPTZ;
ALTER TABLE personal_schedules ADD CONSTRAINT personal_schedule_valid_period
    CHECK (ends_at IS NULL OR ends_at > starts_at);
CREATE INDEX idx_personal_schedules_period ON personal_schedules(user_id, ends_at)
    WHERE ends_at IS NOT NULL;
