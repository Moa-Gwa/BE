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

기존 수집 파이프라인에서 신규 공지에 매핑을 적용한다. 목록에서 다시 발견된 기존 공지는 category가 NULL인 경우에만 채운다. 인스타그램과 직접 등록 공지의 기존 분류도 덮어쓰지 않는다. 현재 목록에 나오지 않는 과거 공지는 아래 API로 보완한다.

## 과거 목록 한 페이지씩 보완

`POST /api/admin/crawler/categories/backfill`

- 운영자 ROLE_OPERATOR만 사용 가능. 일반 단체 관리자·학생은 403, 비로그인은 401.
- sourceId: DB에 등록된 활성 학교 게시판 ID(필수).
- offset: 0~100000, 기본 0. 학교 CMS의 article.offset으로 전달.
- pageSize: 1~50, 기본 10. articleLimit으로 전달. 상단 고정 공지는 추가로 포함될 수 있다.
- dryRun: 기본 true. 시험 실행 후 동일 조건에서 false로 적용.
- 호출당 목록 1회만 요청하며 상세 본문·이미지·첨부파일은 다시 요청하지 않는다. 신규 공지나 알림은 만들지 않는다.
- NULL인 기존 공지만 변경하며, 반복 실행과 동시 분류에서도 기존 값은 보호된다.
- changes는 후보의 공지 ID·원문 분류·저장 코드이며, updated는 실제 UPDATE 건수다. 동시 변경으로 wouldChange와 updated가 다를 수 있다.
- unmappedLabels는 원문 분류가 없거나 매핑되지 않은 값의 집계다. 이미 분류된 공지를 미분류로 되돌리지 않는다.

운영자 로그인으로 받은 쿠키를 `operator.cookies`에 보관한 예시:

```bash
# SOURCE_ID는 아래 조회 결과의 ID로 지정한다.
curl -sS -X POST --cookie operator.cookies \
  -H 'X-Requested-With: fetch' \
  "https://api.moagwa.kr/api/admin/crawler/categories/backfill?sourceId=${SOURCE_ID}&offset=0&pageSize=10&dryRun=true"

# changes의 근거를 확인한 뒤 적용
curl -sS -X POST --cookie operator.cookies \
  -H 'X-Requested-With: fetch' \
  "https://api.moagwa.kr/api/admin/crawler/categories/backfill?sourceId=${SOURCE_ID}&offset=0&pageSize=10&dryRun=false"
```

자동 전체 순회는 제공하지 않는다. 필요한 페이지만 offset을 10씩 증가시키며, 호출 간 최소 2초 이상 간격을 둔다. 오류·EMPTY가 나오면 대상 페이지/선택자를 확인하고 반복 호출을 중단한다. 403·429 응답을 우회하지 않는다.

## 적용 전후 확인

운영 DB와 로컬 DB는 별도로 확인한다. 운영에서는 읽기 조회 → dryRun → 제한된 페이지 적용 → 재조회 순서로 진행한다.

```sql
SELECT s.id, s.code, o.name, COUNT(c.id) FILTER (WHERE c.category IS NULL) AS uncategorized
FROM crawl_sources s JOIN organizations o ON o.id=s.organization_id
LEFT JOIN crawled_notices c ON c.source_id=s.id
WHERE s.enabled GROUP BY s.id,s.code,o.name ORDER BY s.id;

SELECT category,COUNT(*) FROM crawled_notices GROUP BY category ORDER BY COUNT(*) DESC;

-- 장학 관련 제목의 미분류 잔여 건수: 모두 장학으로 바꾸라는 기준이 아니라 점검 지표다.
SELECT COUNT(*) FROM crawled_notices WHERE title LIKE '%장학%' AND category IS NULL;
```

적용 후 `/api/feed?category=SCHOLARSHIP`과 공지 상세 응답을 확인한다. 분류 근거를 얻지 못한 공지는 남겨둔다. 분류 보완은 신규 공지가 아니므로 푸시·조회수 증가를 유발하지 않는다.
