# Moa-Gwa BE
가톨릭대학교 학내 공지와 일정을 통합 제공하는 Spring Boot API 서버


Spring Boot 3.2 / Java 17 / Maven / PostgreSQL (Flyway).

  ## 주요 기능

  - 공지사항 통합 조회 및 검색
  - 학과·학생회별 공지 관리
  - Google / Kakao 로그인
  - Firebase Cloud Messaging 푸시 알림
  - 일반 공지 게시판 크롤링
  - Instagram 학생회 공지 로컬 크롤링
  - 관리자 공지 등록·수정·삭제

  ## 기술 스택

  - Java 21
  - Spring Boot 3.2
  - Spring Data JPA
  - PostgreSQL
  - Flyway
  - Firebase Admin SDK
  - Node.js + Playwright

  ## 로컬 실행

  ```bash
  docker compose up -d
  ./scripts/run-local.sh

  서버 주소: http://localhost:8080

  헬스 체크:

  curl http://localhost:8080/actuator/health

  ## 환경변수

   변수                           설명                                                 
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   JWT_SECRET                     JWT 서명 키
  ─────────────────────────────  ────────────────────────────────
   DB_URL                         운영 PostgreSQL 주소
  ─────────────────────────────  ────────────────────────────────
   DB_USERNAME                    DB 사용자
  ─────────────────────────────  ────────────────────────────────
   DB_PASSWORD                    DB 비밀번호
  ─────────────────────────────  ────────────────────────────────
   FIREBASE_CREDENTIALS_PATH      Firebase 서비스 계정 JSON 경로
  ─────────────────────────────  ────────────────────────────────
   KAKAO_CLIENT_ID                카카오 REST API 키
  ─────────────────────────────  ────────────────────────────────
   GOOGLE_ALLOWED_EMAIL_DOMAIN    Google 로그인 허용 도메인
  ─────────────────────────────  ────────────────────────────────
   INSTAGRAM_SESSION_ID           인스타 크롤러 계정 세션
  ─────────────────────────────  ────────────────────────────────
   INSTAGRAM_CRAWLER_ENABLED      인스타 크롤러 활성화 여부


  ## Instagram 크롤링 순서
<img width="1611" height="1098" alt="image" src="https://github.com/user-attachments/assets/2065fb7b-ab4e-4ca7-a723-26c55c8c42cf" />

  1. 프로필에서 최근 게시물 URL 수집
  2. DB shortcode 중복 검사
  3. 신규 게시물만 상세 페이지 파싱
  4. 캐러셀 이미지 다운로드
  5. uploads/ 저장
  6. 공지 및 이미지 경로 DB 저장


  ## 테스트

  ./mvnw test

  ## 프로젝트 구조

  - auth: 로그인 및 회원 인증
  - notice: 공지 도메인
  - organization: 학과·학생회
  - crawler: 사이트 및 Instagram 크롤러
  - notification: FCM 푸시
  - admin: 관리자 기능
