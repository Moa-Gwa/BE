# 기능별 구현 메모

README에서 옮긴 기능별 동작 기준. 해당 기능을 바꿀 때 함께 갱신함.

## 채팅 실시간(STOMP)

메시지 전송과 실시간 수신은 Swagger에 나오지 않는 STOMP over WebSocket으로 처리. 목록, 이전 메시지, 읽음 처리는 Swagger의 채팅 그룹 참고.

- 연결: `ws://localhost:8080/ws`. 재학생 인증된 세션 쿠키 필요, 허용 출처(`CORS_ALLOWED_ORIGINS`, 기본 `http://localhost:3000`)에서만 연결 가능
- 전송: `SEND /app/chat.send` 본문 `{"roomId":1,"content":"안녕하세요"}` (1~1000자, 방 멤버만)
- 수신: `SUBSCRIBE /user/queue/chat`. 이벤트 `type`별 형식
  - `message`: `{"type":"message","roomId":1,"message":{"id":..,"senderId":..,"senderName":..,"content":..,"createdAt":..}}`
  - `read`: `{"type":"read","roomId":1,"read":{"userId":..,"lastReadId":..}}` 상대가 읽은 위치
  - `room`: `{"type":"room","roomId":1}` 새 방 생성(거래 수락 등). 채팅 목록 다시 조회
- 오류: `SUBSCRIBE /user/queue/errors`. 전송 실패 시 `{"message":"..."}` 수신
- `/user/queue/chat`을 구독 중이 아닌 멤버에게는 FCM 푸시 발송

## 로그인 유지

- Access Token은 `moagwa_session` HttpOnly 쿠키에 보관, 15분간 유효함.
- Refresh Token은 `moagwa_refresh` HttpOnly 쿠키에 보관, DB에는 SHA-256 해시만 저장함.
- `POST /api/auth/refresh`는 Refresh Token을 한 번 소비하고 교체함. 만료는 최초 로그인부터 30일이며 갱신으로 연장되지 않음.
- 갱신 시 학생 인증 상태와 관리자 활성화, 권한을 DB에서 다시 확인함.
- 갱신, 로그아웃에도 쿠키 인증 CSRF 헤더 `X-Requested-With`가 필요함.
- 로그아웃 시 해당 Refresh Token과 두 쿠키를 폐기함. 이미 발급된 Access Token은 최대 15분까지 유효함.
- 자동 갱신은 FE가 담당하므로 BE와 FE를 같은 릴리스로 배포함. 기존 로그인은 Refresh Token이 없어 만료 시 한 번 재로그인이 필요함.

## 저장 공지, 개인 일정 리마인더

- V24 마이그레이션 적용 후 `/api/me/reminders/{MANUAL|CRAWLED|PERSONAL}/{id}` GET/PUT으로 설정합니다. PUT 본문은 `{"enabled":true}`입니다.
- 기본은 OFF이며 저장한 공지의 마감 또는 본인 일정 시작 24시간 전에 한 번 알립니다. 개인 일정 생성 시 `reminderEnabled`도 지정할 수 있습니다.
- 1분마다 최신 날짜와 대상 존재 여부를 확인합니다. 예약 시각이 이미 지난 뒤 켜면 즉시 발송하지 않습니다.
- 날짜 변경은 다음 검사에 반영되고, 저장 취소, 일정 삭제는 발송 대상에서 제외됩니다. 같은 대상 시각은 중복 발송하지 않습니다.
- 장애로 놓친 예약은 최대 1시간까지 지연 처리하며, 이미 마감, 시작한 대상이나 종료 처리한 공지는 제외합니다.
- MY의 공지, 내 일정 리마인더 설정(`deadlineSoon`)이 전체 발송을 제어합니다. 앱 안 알림은 생성되며, 기기 푸시는 별도 등록과 FCM 설정이 필요합니다.
- 크롤링 공지는 저장된 추출 마감일을 사용하므로 원문 확인이 필요합니다. 마감일이 없는 공지에는 리마인더를 설정할 수 없습니다.

## 학교 공지 썸네일, 첨부파일 수집

학교 공지 상세 파싱 시 본문 첫 유효 이미지를 `crawled_notices.thumbnail_url`에 저장한다.
이미지는 본문 HTML에서 그대로 표시하며, 인스타그램 전용 `crawled_notice_images` 갤러리에
추가하지 않는다. 썸네일 후보는 `data-src`(없으면 `src`)를 공지 원문 URL 기준으로 절대화하고
다음 이미지는 제외한다.

- HTTP(S)가 아닌 주소, 비어 있는 주소, 현재 컬럼 제한(500자)을 넘는 주소
- 숨김 요소(`hidden`, `aria-hidden`, `display:none`, `visibility:hidden`) 안의 이미지
- width/height/data-width/data-height 또는 인라인 px 크기가 32 이하인 이미지
- 파일 경로에 icon/ico/logo/blank/spacer/transparent/pixel 토큰이 포함된 이미지

크기를 알 수 없는 본문 이미지는 허용한다. 적절한 이미지가 없으면 썸네일은 null이다.

첨부파일은 원문 전체 문서의 `.b-file-box a[href]`에서 파일명과 URL을 읽는다.
학교, 국어국문학과의 실제 CMS 구조에서 이 선택자를 확인했다. 다운로드 URL은
`?mode=download&articleNo=...&attachNo=...`처럼 확장자가 없어도 수집한다.
첨부파일은 URL 기준 중복을 제거해 `crawled_notice_attachments`에 순서대로 교체 저장한다.
사이트별 구조가 다르면 DB의 선택자만 변경한다.

```sql
UPDATE crawl_sources
SET attachment_selector = '.downloads a[href]'
WHERE code = '해당_소스_코드';
```

상세 API `GET /api/crawled-notices/{id}` 응답에 다음 필드를 추가했다.

```json
{"attachments":[{"fileName":"신청서.hwpx","url":"https://school.example/notice.do?mode=download&attachNo=1"}]}
```

첨부가 없거나 직접 등록 공지라면 `attachments: []`를 반환한다.
피드의 기존 `thumbnail` 필드가 학교 썸네일도 반환하므로 FE는 출처를 구분할 필요가 없다.

### 기존 공지 보완 수집과 재수집

V21 마이그레이션 후 기존 공지는 `media_fetched=false`로 시작한다.
`crawler.enabled=true`이면 기존 크롤러가 한 회차에 소스당 5건씩 상세 페이지를 다시 가져와
썸네일, 첨부파일을 보완한다. 기본 크롤링 주기는 10분이다. 학교 서버에 요청이 몰리지 않도록
보완 수집 건수는 새 공지 수집과 별개로 작게 둔다.
미디어가 없는 정상 공지도 완료 처리하므로 반복해서 요청하지 않는다.
실패하면 기존 미디어를 보존하고 실패 시각(`media_attempted_at`)을 기록해 하루 뒤에 다시 시도한다.
실패한 공지가 다음 배치를 막지 않도록 아직 시도하지 않은 공지를 먼저 처리한다. 비활성 인스타그램 소스는 이 경로에 포함되지 않는다.
보완 수집은 새 공지 알림을 발행하지 않는다.

완료된 학교 공지도 다시 수집해야 한다면 해당 소스만 초기화한 뒤 다음 주기를 기다린다.
원문 페이지 전체를 다시 요청하므로 저장된 본문 HTML에 첨부 영역이 없어도 수집할 수 있다.

```sql
UPDATE crawled_notices n
SET media_fetched = false, media_attempted_at = null
FROM crawl_sources s
WHERE n.source_id = s.id AND s.enabled = true AND s.code = 'catholic_notice';

SELECT s.code, count(*) FILTER (WHERE NOT n.media_fetched) AS pending,
       count(*) FILTER (WHERE n.thumbnail_url IS NOT NULL) AS with_thumbnail
FROM crawled_notices n JOIN crawl_sources s ON s.id = n.source_id
WHERE s.enabled = true GROUP BY s.code ORDER BY s.code;
```

검증: `./mvnw -Dtest=BoardParserTest,CrawlerServiceTest,ApiIntegrationTest test`

## 이미지 블록의 사진 설명

IMAGES 블록에 선택 `captions: string[]` 필드를 추가했다. 기존 `images: string[]`와 같은 순서이며
입력 시 개수가 일치해야 한다. 설명은 사진당 최대 300자이고 빈 문자열은 허용한다.
필드가 생략된 예전 요청은 빈 설명으로 채우고, 기존 저장 JSON의 captions 누락도 허용한다.
생성, 수정, 복제와 학생 상세 응답에 설명이 유지된다. TEXT 블록은 계속 일반 텍스트다.
기존 JSON 컬럼을 사용하므로 추가 DB 마이그레이션은 없다.

검증: `./mvnw -Dtest=ApiIntegrationTest test` (캡션 저장/수정/복제, 이전 JSON, 길이, 개수 검증).

## 로컬 HTTPS 프론트 연결

`./scripts/run-local.sh`는 `local` 프로필을 사용하며 HTTP/HTTPS의 `localhost:3000` 출처를 허용한다.
`.env`에 `CORS_ALLOWED_ORIGINS`를 지정했다면 기본값을 덮어쓰므로 다음처럼 HTTPS도 포함한다.

```dotenv
CORS_ALLOWED_ORIGINS=http://localhost:3000,https://localhost:3000
```

변경 후 백엔드를 재시작한다. 프론트의 HTTPS 개발 서버에서는 `NEXT_PUBLIC_API_BASE_URL=`로 설정해
같은 출처의 `/api` 프록시를 사용한다. 출처 제한과 CSRF 검사는 계속 적용된다.

