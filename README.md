# Moa-Gwa BE

Spring Boot 3.2 / Java 17 / Maven / PostgreSQL (Flyway).

## Run
```bash
docker compose up -d          # local PostgreSQL
./mvnw spring-boot:run        # http://localhost:8080/actuator/health
./mvnw test                   # tests use in-memory H2
```

## Package layout (`com.moagwa`)
`common` · `notice` · `organization` · `user` · `admin` · `crawler`

## Crawler
Sources live in `crawl_sources` (seeded by `V2__seed_...sql`, 45 school sites). Runs every `crawler.interval` (default 10 min); disable with `CRAWLER_ENABLED=false`. The first crawl of a source backfills silently (no events).

Branching and commit rules: see [CONTRIBUTING.md](CONTRIBUTING.md).
