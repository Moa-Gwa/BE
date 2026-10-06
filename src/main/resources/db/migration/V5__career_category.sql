-- 본부 게시판의 '취창업' 분류를 위한 카테고리 (기타는 계속 맨 뒤)
UPDATE categories SET sort_order = 10 WHERE code = 'ETC';
INSERT INTO categories (code, name, sort_order) VALUES ('CAREER', '취창업', 9);
