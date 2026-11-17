package com.moagwa.chatbot;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 챗봇 공지 색인 동기화. 챗봇은 공지를 메모리에만 두므로 BE가 대상 공지 전체를 보냄.
 * 30초마다 목록이 바뀌었는지 보고 바뀌었으면 보내고, 바뀌지 않아도 10분마다 다시 보내 챗봇 재시작과 전송 실패를 복구함
 * 대상과 값의 근거는 챗봇 저장소 docs/decisions.md 10절
 */
@Slf4j
@Component
class ChatbotNoticeSync {

    // 최근 90일 안에 게시됐거나 마감, 행사가 아직 지나지 않은 공지
    private static final String ACTIVE = """
             FROM v_feed_items f
            WHERE f.published_at >= now() - interval '90 days'
               OR f.deadline_at >= now() OR f.event_start_at >= now() OR f.event_end_at >= now()
            """;
    private static final Duration RESEND = Duration.ofMinutes(10);
    private static final int MAX_NOTICES = 3000;

    record Notice(String key, String title, String organization, String body, String url, String publishedAt,
                  String deadlineAt, String eventStartAt, String eventEndAt, String location) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RestClient http;
    private final boolean enabled;
    private final String frontendUrl;
    private String lastFingerprint;
    private Instant lastSent = Instant.EPOCH;

    ChatbotNoticeSync(JdbcTemplate jdbc, Clock clock,
                      @Value("${moagwa.chatbot.url:}") String url,
                      @Value("${moagwa.chatbot.internal-token:}") String token,
                      @Value("${moagwa.frontend-url}") String frontendUrl) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = !url.isBlank() && !token.isBlank();
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(30)); // 챗봇은 받자마자 202로 답하고 색인은 뒤에서 만듦
        this.http = RestClient.builder().baseUrl(url.isBlank() ? "http://localhost" : url).requestFactory(factory)
                .defaultHeader("X-Internal-Token", token).build();
    }

    @Scheduled(fixedDelayString = "${moagwa.chatbot.notice-sync-interval:PT30S}", initialDelayString = "PT30S")
    void tick() {
        if (!enabled) {
            return;
        }
        String fingerprint = fingerprint();
        Instant now = clock.instant();
        if (fingerprint.equals(lastFingerprint) && now.isBefore(lastSent.plus(RESEND))) {
            return;
        }
        try {
            http.put().uri("/v1/notices").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("notices", notices())).retrieve().toBodilessEntity();
            lastFingerprint = fingerprint;
            lastSent = now;
        } catch (RestClientException e) { // 챗봇이 준비 중(503)이거나 꺼져 있으면 다음 확인 때 다시 보냄
            log.warn("챗봇 공지 동기화 실패: {}", e.getMessage());
        }
    }

    /** 대상 공지의 내용이 하나라도 바뀌면 달라지는 값. 바뀐 게 없으면 본문을 읽어 보내지 않음 */
    String fingerprint() {
        return jdbc.queryForObject("""
                SELECT md5(coalesce(string_agg(f.source || ':' || f.id || ':' || md5(concat_ws('|', f.title,
                       f.organization_name, f.body, f.published_at, f.deadline_at, f.event_start_at, f.event_end_at,
                       f.location)), ',' ORDER BY f.source, f.id), ''))
                """ + ACTIVE, String.class);
    }

    List<Notice> notices() {
        // 챗봇 쪽 길이 한도(제목 300, 본문 8,000, 장소 200자)에 맞춰 잘라 보냄. 하나라도 넘으면 목록 전체가 거부됨
        return jdbc.query("SELECT f.source, f.id, left(f.title, 300) AS title, f.organization_name, left(f.body, 8000) AS body,"
                        + " f.published_at, f.deadline_at, f.event_start_at, f.event_end_at, left(f.location, 200) AS location"
                        + ACTIVE + " ORDER BY f.published_at DESC LIMIT " + MAX_NOTICES,
                (rs, i) -> new Notice(rs.getString("source") + ":" + rs.getLong("id"), rs.getString("title"),
                        rs.getString("organization_name"), rs.getString("body"),
                        frontendUrl + "/notices/" + rs.getString("source").toLowerCase() + "/" + rs.getLong("id"),
                        iso(rs.getTimestamp("published_at")), iso(rs.getTimestamp("deadline_at")),
                        iso(rs.getTimestamp("event_start_at")), iso(rs.getTimestamp("event_end_at")),
                        rs.getString("location")));
    }

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }
}
