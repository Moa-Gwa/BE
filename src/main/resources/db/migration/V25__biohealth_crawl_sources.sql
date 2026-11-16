-- 바이오헬스융합대학의 신규 수집처. 기존 학교 공통 게시판 선택자를 사용한다.
INSERT INTO organizations (name, type, college, is_verified) VALUES
    ('AI의공학과', 'DEPARTMENT', '바이오헬스융합대학', TRUE),
    ('바이오로직스공학부', 'DEPARTMENT', '바이오헬스융합대학', TRUE);

INSERT INTO crawl_sources (code, organization_id, list_url, enabled)
SELECT v.code, o.id, v.list_url, TRUE FROM (VALUES
    ('dept_ai_biomedical', 'AI의공학과', 'https://aibme.catholic.ac.kr/aibme/community/notice.do'),
    ('dept_biologics', '바이오로직스공학부', 'https://ble.catholic.ac.kr/BLE/community/notice.do')
) AS v(code, org_name, list_url) JOIN organizations o ON o.name = v.org_name;
