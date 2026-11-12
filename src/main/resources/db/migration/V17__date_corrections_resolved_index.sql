-- 처리 내역(반영함·반려함)을 최근 처리순으로 조회
CREATE INDEX idx_date_corrections_resolved ON notice_date_corrections (status, resolved_at DESC);
