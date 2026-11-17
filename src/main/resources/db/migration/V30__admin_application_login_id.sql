-- 신청자가 로그인 아이디를 직접 정함. 기존 신청은 지금까지처럼 신청 메일을 아이디로 씀
ALTER TABLE admin_applications ADD COLUMN login_id VARCHAR(200);
UPDATE admin_applications SET login_id = email;
ALTER TABLE admin_applications ALTER COLUMN login_id SET NOT NULL;

-- 대기 중이거나 승인된 신청끼리는 아이디가 겹치지 않음
CREATE UNIQUE INDEX uq_admin_applications_active_login_id ON admin_applications (lower(login_id))
    WHERE status IN ('PENDING', 'APPROVED');
