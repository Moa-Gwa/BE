# Moa-Gwa BE

Spring Boot 3.2 / Java 17 / Maven / PostgreSQL (Flyway).

## Run
```bash
docker compose up -d          # local PostgreSQL (or: scripts/dev-db.sh start, no Docker needed)
./mvnw spring-boot:run        # http://localhost:8080/actuator/health
./mvnw test                   # tests use in-memory H2
```

## Push notifications (FCM)
Web push is sent by `notification/PushSender` after a notice is saved. It needs a Firebase **service-account key** (JSON).
- Keep the key **outside the repo**: `~/.secrets/moagwa-firebase-adminsdk.json` (never commit it).
- Run with `scripts/run-local.sh` — it picks the key up automatically. Or set `FIREBASE_CREDENTIALS_PATH=/path/to/key.json` yourself (production: mount the key as a secret file).
- Without a key the server still starts; push delivery is skipped and the log says `FCM push disabled`.
- Devices register through `PUT/DELETE /api/me/push-tokens` (table `device_tokens`).

## Google sign-in (Firebase)
Students can sign in with Google. The browser signs in through Firebase (popup) and sends the Firebase **ID token** to `POST /api/auth/google`; the server verifies it with the Firebase service-account key (same key as push) and issues our own JWT. Users are stored with `auth_provider = GOOGLE`.
- Needs the Firebase key (`FIREBASE_CREDENTIALS_PATH`, see above). Without it the endpoint answers `503`.
- Optional: `GOOGLE_ALLOWED_EMAIL_DOMAIN=school.ac.kr` limits sign-in to one email domain (empty = any Google account).
- Firebase console: Authentication → Sign-in method → Google must be enabled, and the site's domain must be in *Authorized domains* (`localhost` is there by default).

## Package layout (`com.moagwa`)
`common` · `notice` · `organization` · `user` · `admin` · `crawler` · `notification`

## Local login
With the `local` profile (default) sample data is created: admins `student-council`, `ai-council`, `cs-council` (password `moagwa1234`). Students sign in with `POST /api/auth/dev-student`; Kakao login needs `KAKAO_CLIENT_ID`.

## Crawler
Sources live in `crawl_sources` (seeded by `V2__seed_...sql`, 45 school sites). Runs every `crawler.interval` (default 10 min); disable with `CRAWLER_ENABLED=false`. The first crawl of a source backfills silently (no events).

Branching and commit rules: see [CONTRIBUTING.md](CONTRIBUTING.md).
