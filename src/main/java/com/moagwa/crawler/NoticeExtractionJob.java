package com.moagwa.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.moagwa.ai.GeminiClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 본문을 수집한 크롤링 공지에서 마감일·행사일·카테고리·키워드를 추출해 저장.
 * 새 공지와 기존 공지 모두 추출 전 상태로 남아 있으면 최신순으로 조금씩 처리한다.
 * 실패한 공지는 다음 주기에 다시 시도하고, 정해진 횟수를 넘으면 건너뛴다.
 */
@Slf4j
@Component
class NoticeExtractionJob {

    static final int MAX_ATTEMPTS = 3;

    record Pending(long id, String title, String text, Instant publishedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final GeminiClient gemini;
    private final NoticeExtractor extractor;
    private final int batchSize;
    /** Gemini 응답 대기가 크롤러 등 다른 예약 작업의 공용 스케줄러 스레드를 막지 않도록 전용 스레드에서 실행 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notice-extraction");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    NoticeExtractionJob(NamedParameterJdbcTemplate jdbc, GeminiClient gemini,
                        @Value("${moagwa.gemini.extraction.default-deadline-time:18:00}") LocalTime defaultDeadlineTime,
                        @Value("${moagwa.gemini.extraction.batch-size:10}") int batchSize) {
        this.jdbc = jdbc;
        this.gemini = gemini;
        this.extractor = new NoticeExtractor(defaultDeadlineTime);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${moagwa.gemini.extraction.interval:PT1M}",
            initialDelayString = "${moagwa.gemini.extraction.initial-delay:PT1M}")
    void run() {
        // 이전 묶음이 아직 처리 중이면 이번 주기는 건너뜀
        if (!gemini.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                extractPending(batchSize);
            } catch (RuntimeException e) {
                log.warn("Notice extraction batch failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    /** 추출 전 공지를 최대 limit건 처리하고 성공한 수 반환 */
    int extractPending(int limit) {
        List<String> categories = jdbc.queryForList("SELECT code FROM categories ORDER BY sort_order",
                new MapSqlParameterSource(), String.class);
        int done = 0;
        for (Pending n : pending(limit)) {
            try {
                JsonNode answer = gemini.generateJson(extractor.prompt(n.title(), n.text(), n.publishedAt(), categories),
                        NoticeExtractor.schema(categories));
                save(n.id(), extractor.parse(answer, n.title(), n.text(), n.publishedAt(), categories));
                done++;
            } catch (GeminiClient.UnavailableException e) {
                log.info("Notice extraction paused after {} notices: {}", done, e.getMessage());
                break; // 남은 공지는 다음 주기에 처리
            } catch (RuntimeException e) {
                log.warn("Notice extraction failed: noticeId={}", n.id(), e);
                jdbc.update("UPDATE crawled_notices SET extraction_attempts = extraction_attempts + 1 WHERE id = :id",
                        new MapSqlParameterSource("id", n.id()));
            }
        }
        return done;
    }

    private List<Pending> pending(int limit) {
        return jdbc.query("""
                SELECT id, title, content_text, published_at FROM crawled_notices
                 WHERE extracted_at IS NULL AND content_fetched AND extraction_attempts < :max
                   AND content_text IS NOT NULL AND btrim(content_text) <> ''
                 ORDER BY published_at DESC LIMIT :limit""",
                new MapSqlParameterSource().addValue("max", MAX_ATTEMPTS).addValue("limit", limit),
                (rs, i) -> new Pending(rs.getLong("id"), rs.getString("title"), rs.getString("content_text"),
                        rs.getTimestamp("published_at").toInstant()));
    }

    /** 게시판 분류로 정해진 카테고리는 유지하고 비어 있을 때만 채움. 운영자가 교정한 날짜는 덮어쓰지 않음 */
    private void save(long id, NoticeExtractor.Result r) {
        jdbc.update("""
                UPDATE crawled_notices
                   SET deadline_at = CASE WHEN deadline_locked THEN deadline_at ELSE :deadline END,
                       event_start_at = CASE WHEN event_locked THEN event_start_at ELSE :eventStart END,
                       event_end_at = CASE WHEN event_locked THEN event_end_at ELSE :eventEnd END,
                       -- 근거 문장은 두 날짜를 합친 값이라 한쪽이라도 교정되면 갱신하지 않음
                       date_evidence = CASE WHEN deadline_locked OR event_locked THEN date_evidence ELSE :evidence END,
                       keywords = :keywords, category = COALESCE(category, :category),
                       extracted_at = now()
                 WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id)
                        .addValue("deadline", ts(r.deadlineAt())).addValue("eventStart", ts(r.eventStartAt()))
                        .addValue("eventEnd", ts(r.eventEndAt())).addValue("evidence", r.evidence())
                        .addValue("keywords", r.keywords().toArray(String[]::new)).addValue("category", r.category()));
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }
}
