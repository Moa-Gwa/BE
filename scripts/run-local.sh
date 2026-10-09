#!/usr/bin/env bash
# 로컬 서버 실행. FCM 서비스 계정 키가 있으면 푸시 발송을 켠다.
#   키 위치: $FIREBASE_CREDENTIALS_PATH, 없으면 ~/.secrets/moagwa-firebase-adminsdk.json
#   키는 저장소 밖에 두고 절대 커밋하지 않는다. 키가 없어도 서버는 뜨고 푸시 발송만 건너뛴다.
set -euo pipefail
cd "$(dirname "$0")/.."

# Keep local-only credentials out of Git while making them available to Spring and the local Node collector.
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi

KEY="${FIREBASE_CREDENTIALS_PATH:-$HOME/.secrets/moagwa-firebase-adminsdk.json}"
if [ -f "$KEY" ]; then
  export FIREBASE_CREDENTIALS_PATH="$KEY"
else
  unset FIREBASE_CREDENTIALS_PATH
  echo "[run-local] FCM 키 없음 ($KEY) -> 푸시 발송 비활성" >&2
fi

exec ./mvnw spring-boot:run "$@"
