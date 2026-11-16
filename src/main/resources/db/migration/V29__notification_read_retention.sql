CREATE INDEX idx_notifications_read_at ON notifications(read_at) WHERE read_at IS NOT NULL;
