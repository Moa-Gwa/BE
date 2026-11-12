-- 로그인 학생의 공지별 최초 조회. 기존 누적 조회수는 유지한다.
CREATE TABLE notice_view_records (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source VARCHAR(20) NOT NULL CHECK (source IN ('MANUAL', 'CRAWLED')),
    notice_id BIGINT NOT NULL,
    viewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, source, notice_id)
);
