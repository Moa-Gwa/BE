# Instagram 로컬 브라우저 스크래퍼

Spring 백엔드가 자식 프로세스로 실행하는 Node.js + Playwright 도우미다. Apify Actor, API 토큰, 프록시, 클라우드 런타임, 호출당 과금은 사용하지 않는다.

백엔드는 아래 순서로 스크립트를 실행한다.

1. `collect-profile-links.js`가 등록된 프로필을 열고 최근 게시물 URL만 반환한다.
2. 백엔드가 각 shortcode를 DB와 대조한다.
3. `collect-post-details.js`가 DB에 없는 게시물만 열어 캐러셀 이미지 URL과 메타데이터를 추출해 JSON으로 반환한다.
4. 백엔드는 이미지를 `BE/uploads`에 저장하고 공지를 등록한다.

## 서버 실행 환경

백엔드가 실행되는 모든 컴퓨터에서 Node.js 20 이상과 브라우저 바이너리를 한 번 설치한다.

```bash
cd BE/instagram-browser-scraper
npm ci
npx playwright install chromium
```

크롤러 전용 계정의 `sessionid` 쿠키는 서버 환경변수 또는 로컬 개발용으로 무시되는 `BE/.env`에만 설정한다.

```bash
INSTAGRAM_SESSION_ID=...
INSTAGRAM_CRAWLER_ENABLED=true
```

세션 값은 절대 커밋하지 않는다. 브라우저에는 `instagram.com` 쿠키로만 전달되며 제3자 서비스로 전송하지 않는다.

## 로컬 검증

```bash
npm test
node --check src/collect-profile-links.js
node --check src/collect-post-details.js
```
