-- 학과명 정정(학과 학생회 이름 "국사학과 학생회"와 맞춤)
UPDATE organizations SET name = '국사학과' WHERE name = '사학과' AND type = 'DEPARTMENT';
