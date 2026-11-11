package com.moagwa.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moagwa.ai.GeminiClient;
import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNotice;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NoticeExtractionJobTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired NoticeExtractionJob job;
    @Autowired OrganizationRepository organizations;
    @Autowired CrawlSourceRepository sources;
    @Autowired CrawledNoticeRepository crawled;
    @MockBean GeminiClient gemini;

    private String tag;
    private long noticeId;
    private LocalDate deadline;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        // 이전 테스트가 남긴 대기 공지보다 먼저 처리되도록 가장 최근 게시일로 저장
        Instant published = Instant.now().plus(1, ChronoUnit.DAYS);
        deadline = LocalDate.ofInstant(published, NoticeExtractor.KST).plusDays(10);
        Organization dept = organizations.save(new Organization("학과-" + tag, OrganizationType.DEPARTMENT));
        CrawlSource source = sources.save(new CrawlSource("src-" + tag, dept, "https://example.com/list"));
        CrawledNotice c = new CrawledNotice(source, tag, "장학 신청 안내 " + tag, "https://example.com/" + tag, false,
                published, published);
        String body = "신청 마감: " + deadline.getMonthValue() + "월 " + deadline.getDayOfMonth() + "일 17:00까지";
        c.applyContent("<p>" + body + "</p>", body);
        noticeId = crawled.save(c).getId();
        when(gemini.enabled()).thenReturn(true);
    }

    @Test
    void extractedDeadlineShowsInFeedSearchAndDetail() throws Exception {
        ObjectNode answer = json.createObjectNode();
        answer.put("deadlineDate", deadline.toString()).put("deadlineTime", "17:00")
                .put("deadlineEvidence", deadline.getMonthValue() + "월 " + deadline.getDayOfMonth() + "일 17:00까지")
                .put("category", "SCHOLARSHIP").put("confidence", "HIGH");
        answer.putArray("keywords").add("키워드" + tag).add("국가장학금");
        when(gemini.generateJson(anyString(), anyMap())).thenReturn(answer);

        assertThat(job.extractPending(1)).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT category, extracted_at, deadline_at FROM crawled_notices WHERE id = ?", noticeId);
        assertThat(row.get("category")).isEqualTo("SCHOLARSHIP");
        assertThat(row.get("extracted_at")).isNotNull();
        mvc.perform(get("/api/feed").param("q", "키워드" + tag))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].category").value("SCHOLARSHIP"))
                .andExpect(jsonPath("$.items[0].deadlineAt").exists());
        mvc.perform(get("/api/feed").param("q", tag).param("category", "SCHOLARSHIP"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/crawled-notices/" + noticeId))
                .andExpect(jsonPath("$.deadlineAt").exists())
                .andExpect(jsonPath("$.dateEvidence").value(answer.get("deadlineEvidence").asText()))
                .andExpect(jsonPath("$.keywords[1]").value("국가장학금"));
    }

    @Test
    void operatorCorrectedDatesAreNotOverwritten() {
        jdbc.update("UPDATE crawled_notices SET deadline_at = '2030-01-01T00:00:00Z', dates_locked = true WHERE id = ?",
                noticeId);
        ObjectNode answer = json.createObjectNode();
        answer.put("deadlineDate", deadline.toString())
                .put("deadlineEvidence", deadline.getMonthValue() + "월 " + deadline.getDayOfMonth() + "일 17:00까지")
                .put("category", "SCHOLARSHIP").put("confidence", "HIGH");
        answer.putArray("keywords").add("장학");
        when(gemini.generateJson(anyString(), anyMap())).thenReturn(answer);

        assertThat(job.extractPending(1)).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT deadline_at, keywords::text AS keywords FROM crawled_notices WHERE id = ?", noticeId);
        assertThat(((java.sql.Timestamp) row.get("deadline_at")).toInstant()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(row.get("keywords")).isEqualTo("{장학}"); // 날짜 외 값은 갱신
    }

    @Test
    void failedNoticesAreRetriedThenSkipped() {
        when(gemini.generateJson(anyString(), anyMap())).thenThrow(new IllegalStateException("boom"));

        for (int i = 0; i < NoticeExtractionJob.MAX_ATTEMPTS; i++) {
            job.extractPending(1);
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT extraction_attempts, extracted_at FROM crawled_notices WHERE id = ?", noticeId);
        assertThat(row.get("extraction_attempts")).isEqualTo(NoticeExtractionJob.MAX_ATTEMPTS);
        assertThat(row.get("extracted_at")).isNull();
        // 시도 횟수를 넘긴 공지는 더 이상 고르지 않음
        jdbc.update("UPDATE crawled_notices SET extracted_at = now() WHERE extracted_at IS NULL AND id <> ?", noticeId);
        assertThat(job.extractPending(1)).isZero();
    }

    @Test
    void rateLimitStopsTheBatchWithoutCountingAFailure() {
        when(gemini.generateJson(anyString(), anyMap())).thenThrow(new GeminiClient.UnavailableException("busy", null));

        assertThat(job.extractPending(5)).isZero();

        assertThat(jdbc.queryForObject("SELECT extraction_attempts FROM crawled_notices WHERE id = ?", Integer.class,
                noticeId)).isZero();
    }
}
