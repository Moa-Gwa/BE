# 학생회 초기 데이터 운영 반영

기존 API를 그대로 사용한다. 학생회 목록은 `/api/organizations?type=STUDENT_GOVERNMENT`,
공지는 `/api/feed?orgType=STUDENT_GOVERNMENT`와 `/api/crawled-notices/{id}`로 조회된다.
FE 수정과 새 DB 마이그레이션은 필요 없다.

## 포함되는 데이터

- `db/cuk_instagram_notices_sept2026.json`: 학생회·인스타그램 계정·공지 스냅샷
- `static/instagram/manifest.json` 및 JPG: 기존 공지 이미지
- 적재 대상: `organizations`, `crawl_sources`, `crawled_notices` 및 공지 이미지 컬렉션

JSON과 이미지는 BE 소스에 포함되어 있어 운영 이미지 빌드 시 JAR에 함께 들어간다.
이 기능은 저장소에 있는 과거 스냅샷을 적재하며, 로컬 DB 전체나 최신 브라우저 수집 결과를 복사하지 않는다.

## 실행 순서

1. 변경된 BE 이미지를 배포한다. 운영 프로필은 `prod`를 유지한다.
2. **한 인스턴스에만** `INSTAGRAM_SNAPSHOT_IMPORT_ENABLED=true`를 설정하고 시작한다.
   다른 인스턴스에서는 옵션을 끈다. 실행 중인 인스타그램 크롤러가 있다면 적재 중에는 중지한다.
3. 로그에서 다음 완료 메시지를 확인한다.

   ```text
   Instagram student-council snapshot imported: N new notices, M existing notices skipped
   ```

4. API에서 학생회 목록, 피드, 상세 본문과 이미지가 표시되는지 확인한다.
5. 옵션을 제거하거나 `INSTAGRAM_SNAPSHOT_IMPORT_ENABLED=false`로 바꾸고 다시 시작한다.

운영 기본값은 `false`다. `local` 프로필은 기존 개발 편의를 위해 기본값 `true`를 유지하며,
같은 환경변수로 끌 수 있다. 개발 계정·거래 샘플을 넣는 `local` 프로필을 운영에서 켜지 않는다.

## 재실행과 알림

- 기존 수집 소스는 계정별 `instagram_student_council_{계정}` 코드로 재사용한다.
- 소스가 없을 때만 `{학과명} 학생회` 이름의 조직을 재사용하거나 만든다.
- 기존 공지는 `(source_id, external_id)`가 같으면 건너뛴다.
  기존 본문·카테고리·이미지·조회수·저장 정보는 덮어쓰지 않는다.
- 적재 중 오류가 발생하면 단일 트랜잭션을 롤백한다.
- 이 적재 경로에서는 신규 공지 이벤트를 발행하지 않으므로 과거 공지 알림·푸시를 보내지 않는다.
- 인스타그램 소스는 HTML 크롤러가 순회하지 않도록 `enabled=false`로 등록한다.

실시간 인스타그램 수집 활성화는 별도 작업이다. 초기 적재에는 Node.js·Playwright·인스타그램 세션이 필요 없다.
