# 크롤링 공지 분류 및 기존 데이터 보완

## 분류 기준

제목·본문 키워드 추측은 사용하지 않는다. 목록 행의 `.b-cate`에 표시된 원문 분류만 사용한다.

| 원문 분류 | 저장 코드 |
| --- | --- |
| 학사 | ACADEMIC |
| 장학, 장학금 | SCHOLARSHIP |
| 복지 | WELFARE |
| 행사 | EVENT |
| 간식 | SNACK |
| 제휴 | PARTNERSHIP |
| 학생회비 | STUDENT_FEE |
| 모집 | RECRUIT |
| 취창업, 취업, 창업, 취업·창업 | CAREER |
| 기타 | ETC |

공백·탭·줄바꿈·NBSP는 정규화한다. 분류가 없거나 `일반`, `공지`, `전체`, `학과공지`, `커리어` 등 표에 없는 값은 미분류로 유지한다. 원문에 분류가 없으면 장학이라는 제목만으로 SCHOLARSHIP을 부여하지 않는다.

## 신규/정기 수집

기존 수집 파이프라인에서 신규 공지에 매핑을 적용한다. 목록에서 다시 발견된 기존 공지는 category가 NULL인 경우에만 채운다. 인스타그램과 직접 등록 공지의 기존 분류도 덮어쓰지 않는다. 현재 목록에 나오지 않는 과거 공지는 미분류로 남는다.

## 미분류 현황 확인

운영 DB와 로컬 DB는 별도로 확인한다. 아래 조회로 미분류 건수를 확인한다.

```sql
SELECT s.id, s.code, o.name, COUNT(c.id) FILTER (WHERE c.category IS NULL) AS uncategorized
FROM crawl_sources s JOIN organizations o ON o.id=s.organization_id
LEFT JOIN crawled_notices c ON c.source_id=s.id
WHERE s.enabled GROUP BY s.id,s.code,o.name ORDER BY s.id;

SELECT category,COUNT(*) FROM crawled_notices GROUP BY category ORDER BY COUNT(*) DESC;

-- 장학 관련 제목의 미분류 잔여 건수: 모두 장학으로 바꾸라는 기준이 아니라 점검 지표다.
SELECT COUNT(*) FROM crawled_notices WHERE title LIKE '%장학%' AND category IS NULL;
```

분류 결과는 `/api/feed?category=SCHOLARSHIP`과 공지 상세 응답으로 확인한다. 분류 근거를 얻지 못한 공지는 미분류로 남긴다. 정기 수집의 분류 보완은 신규 공지가 아니므로 푸시와 조회수 증가를 유발하지 않는다.
