CREATE TABLE admin_applications (
    id                BIGSERIAL PRIMARY KEY,
    organization_name VARCHAR(100) NOT NULL,
    university        VARCHAR(100) NOT NULL,
    department        VARCHAR(100) NOT NULL,
    contact_name      VARCHAR(50)  NOT NULL,
    email             VARCHAR(200) NOT NULL,
    phone             VARCHAR(40)  NOT NULL,
    channel_url       VARCHAR(500) NOT NULL,
    reason            TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    rejection_reason  VARCHAR(500),
    organization_id   BIGINT REFERENCES organizations (id),
    admin_id          BIGINT REFERENCES admins (id),
    setup_token_hash  VARCHAR(64),
    setup_expires_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ
);

CREATE INDEX idx_admin_applications_status ON admin_applications (status, created_at);

-- 기존 계정은 활성 상태 유지. 신청으로 발급된 계정은 비밀번호 설정 전까지 false
ALTER TABLE admins ADD COLUMN activated BOOLEAN NOT NULL DEFAULT TRUE;
