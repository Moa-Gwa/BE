#!/usr/bin/env bash
# Local PostgreSQL for development without Docker (uses the Homebrew postgres binaries).
# Usage: scripts/dev-db.sh start|stop|status   (data lives in .local-pg/, port 5432, db/user/password: moagwa)
set -euo pipefail
cd "$(dirname "$0")/.."
BIN="$(dirname "$(readlink -f "$(command -v postgres)")")"
DATA=".local-pg"

case "${1:-start}" in
  start)
    if [ ! -d "$DATA" ]; then
      "$BIN/initdb" -D "$DATA" -U moagwa -A trust >/dev/null
    fi
    "$BIN/pg_ctl" -D "$DATA" -o "-p 5432 -k /tmp" -l "$DATA/server.log" -w start >/dev/null || true
    psql -h localhost -U moagwa -d postgres -tc "SELECT 1 FROM pg_database WHERE datname='moagwa'" | grep -q 1 \
      || psql -h localhost -U moagwa -d postgres -qc "CREATE DATABASE moagwa"
    echo "PostgreSQL ready on localhost:5432 (db moagwa)"
    ;;
  stop)   "$BIN/pg_ctl" -D "$DATA" -m fast stop ;;
  status) "$BIN/pg_ctl" -D "$DATA" status ;;
esac
