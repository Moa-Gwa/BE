-- 운영자가 교정한 날짜. true면 자동 추출 결과로 덮어쓰지 않음
ALTER TABLE crawled_notices ADD COLUMN dates_locked BOOLEAN NOT NULL DEFAULT false;

-- 크롤링 공지 날짜 교정 요청. 학생이 요청하고 운영자가 반영·반려
CREATE TABLE notice_date_corrections (
    id                 BIGSERIAL PRIMARY KEY,
    crawled_notice_id  BIGINT       NOT NULL REFERENCES crawled_notices (id) ON DELETE CASCADE,
    user_id            BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    field              VARCHAR(20)  NOT NULL CHECK (field IN ('DEADLINE', 'EVENT')),
    -- 학생이 제안한 날짜. 둘 다 null이면 "날짜 없음" 제안
    suggested_start    DATE,
    suggested_end      DATE,
    memo               VARCHAR(300),
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED')),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMPTZ,
    resolved_by        BIGINT       REFERENCES admins (id),
    CHECK (suggested_end IS NULL OR suggested_start IS NOT NULL AND suggested_end >= suggested_start)
);

-- 같은 공지·항목에 대기 중인 본인 요청은 1건
CREATE UNIQUE INDEX uq_date_corrections_pending ON notice_date_corrections (crawled_notice_id, user_id, field)
    WHERE status = 'PENDING';
CREATE INDEX idx_date_corrections_status ON notice_date_corrections (status, created_at);
