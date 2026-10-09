-- 푸시(FCM) 수신 기기. 한 사용자가 여러 기기를 쓸 수 있고, 토큰 하나는 한 사용자에게만 속한다.
CREATE TABLE device_tokens (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token        VARCHAR(512)  NOT NULL UNIQUE,
    user_agent   VARCHAR(300),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);
