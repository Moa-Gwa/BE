-- 운영자 공지는 모든 학생이 받아야 하므로 관심 단체 설정과 무관하게 항상 포함되는 필수 단체로 표시
ALTER TABLE organizations ADD COLUMN is_required BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO organizations (name, type, is_verified, is_required)
VALUES ('운영자', 'STUDENT_GOVERNMENT', TRUE, TRUE);

-- 총학생회 등에 소속돼 있던 운영자 계정과 그 계정이 쓴 공지를 운영자 단체로 이전
-- 설정(moagwa.operator-login-ids)으로만 운영자가 된 계정도 포함
UPDATE notices SET organization_id = (SELECT id FROM organizations WHERE name = '운영자')
 WHERE author_id IN (SELECT id FROM admins WHERE role = 'OPERATOR' OR login_id = 'operator'
    OR login_id = ANY (string_to_array(replace('${operator_login_ids}', ' ', ''), ',')));

UPDATE admins SET organization_id = (SELECT id FROM organizations WHERE name = '운영자')
 WHERE role = 'OPERATOR' OR login_id = 'operator'
    OR login_id = ANY (string_to_array(replace('${operator_login_ids}', ' ', ''), ','));
