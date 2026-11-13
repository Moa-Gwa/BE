-- 발송 실패도 포함한 사용자별 테스트 알림 시도 간격. 토큰 재등록으로 우회할 수 없다.
CREATE TABLE push_test_attempts (
    user_id      BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    attempted_at TIMESTAMPTZ NOT NULL
);
