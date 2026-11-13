-- 원문 토큰은 저장하지 않으며, 교체 후 이전 토큰은 다시 사용할 수 없다.
CREATE TABLE refresh_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    principal_id BIGINT NOT NULL,
    role VARCHAR(10) NOT NULL CHECK (role IN ('STUDENT', 'ADMIN')),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_refresh_sessions_expiry ON refresh_sessions (expires_at);
