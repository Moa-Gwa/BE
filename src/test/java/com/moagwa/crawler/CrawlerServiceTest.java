package com.moagwa.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawlStatus;
import com.moagwa.crawler.domain.CrawledNotice;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.crawler.fetch.PageFetcher;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@SpringBootTest
@RecordApplicationEvents
class CrawlerServiceTest {

    private static final String LIST_URL = "https://www.catholic.ac.kr/ko/campuslife/notice.do";

    @Autowired CrawlerService crawler;
    @Autowired CrawlSourceRepository sources;
    @Autowired CrawledNoticeRepository notices;
    @Autowired OrganizationRepository organizations;
    @Autowired ApplicationEvents events;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.moagwa.feed.NoticeQueryService details;
    @MockBean PageFetcher fetcher;

    private CrawlSource source;

    @BeforeEach
    void setUp() {
        sources.findAll().stream().filter(s -> s.getCode().equals("test_source")).forEach(s -> {
            notices.deleteAll(notices.findAll().stream().filter(n -> n.getSource().getId().equals(s.getId())).toList());
            sources.delete(s);
        });
        Organization org = organizations.findAll().stream().filter(o -> o.getName().equals("테스트 조직")).findFirst()
                .orElseGet(() -> organizations.save(new Organization("테스트 조직", OrganizationType.UNIVERSITY)));
        source = sources.save(new CrawlSource("test_source", org, LIST_URL));
    }

    /** 다른 테스트가 남긴 공지는 제외하고 이 소스의 공지만 셈 */
    private List<CrawledNotice> mine() {
        return notices.findAll().stream().filter(n -> n.getSource().getId().equals(source.getId())).toList();
    }

    @Test
    void firstCrawlBackfillsNoticesWithContentAndPublishesNoEvents() throws IOException {
        stubSite();

        CrawlResult result = crawler.crawlSource(source);

        assertThat(result.status()).isEqualTo(CrawlStatus.SUCCESS);
        assertThat(result.newNotices()).isEqualTo(4);
        List<CrawledNotice> saved = mine();
        assertThat(saved).hasSize(4).allMatch(CrawledNotice::isContentFetched);
        assertThat(saved).anyMatch(n -> n.getContentText().contains("가톨릭대학교 학생 여러분"));
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).isEmpty();
        assertThat(sources.findById(source.getId()).orElseThrow().getLastCrawledAt()).isNotNull();
    }

    @Test
    void secondCrawlDoesNotDuplicateAndOnlyNewPostsPublishEvents() throws IOException {
        stubSite();
        crawler.crawlSource(source);
        notices.findAll().stream()
                .filter(n -> n.getExternalId().equals("276440"))
                .forEach(notices::delete); // pretend 276440 is a brand-new post
        CrawlSource reloaded = sources.findById(source.getId()).orElseThrow();

        CrawlResult result = crawler.crawlSource(reloaded);

        assertThat(result.newNotices()).isEqualTo(1);
        assertThat(mine()).hasSize(4);
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).hasSize(1)
                .first().satisfies(e -> assertThat(e.title()).isNotBlank());
    }

    @Test
    void savesNoticeWithoutBodyWhenDetailFetchFailsAndFillsItLater() throws IOException {
        doThrow(new IOException("timeout")).when(fetcher).fetch(anyString());
        doReturn(load("catholic-list.html", LIST_URL)).when(fetcher).fetch(LIST_URL);

        crawler.crawlSource(source);
        assertThat(mine()).hasSize(4).noneMatch(CrawledNotice::isContentFetched);

        stubSite();
        crawler.crawlSource(sources.findById(source.getId()).orElseThrow());

        assertThat(mine()).hasSize(4).allMatch(CrawledNotice::isContentFetched);
    }

    @Test
    void recordsFailureAfterRetriesWithoutThrowing() throws IOException {
        doThrow(new IOException("connection refused")).when(fetcher).fetch(anyString());

        CrawlResult result = crawler.crawlSource(source);

        assertThat(result.isFailed()).isTrue();
        CrawlSource stored = sources.findById(source.getId()).orElseThrow();
        assertThat(stored.getLastStatus()).isEqualTo(CrawlStatus.FAILED);
        assertThat(stored.getLastError()).contains("connection refused");
        assertThat(stored.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void flagsSourceWhenNoRowsCanBeParsed() throws IOException {
        doReturn(Jsoup.parse("<html><body>변경된 화면</body></html>", LIST_URL)).when(fetcher).fetch(anyString());

        CrawlResult result = crawler.crawlSource(source);

        assertThat(result.status()).isEqualTo(CrawlStatus.EMPTY);
        assertThat(sources.findById(source.getId()).orElseThrow().getLastStatus()).isEqualTo(CrawlStatus.EMPTY);
    }

    @Test
    void existingNoticesBackfillMediaAndRepeatedCollectionReplacesAttachments() throws IOException {
        stubSite();
        crawler.crawlSource(source);
        long id = mine().get(0).getId();
        jdbc.update("UPDATE crawled_notices SET media_fetched = false, thumbnail_url = null WHERE source_id = ?", source.getId());
        doReturn(load("board-media-detail.html", LIST_URL)).when(fetcher).fetch(anyString());
        doReturn(load("catholic-list.html", LIST_URL)).when(fetcher).fetch(LIST_URL);
        crawler.crawlSource(sources.findById(source.getId()).orElseThrow());
        // 학교 서버 부담을 줄이려고 한 회차에 소스당 5건까지만 보완함
        assertThat(mine()).filteredOn(CrawledNotice::isMediaFetched).hasSize(Math.min(5, mine().size()));
        for (int run = 0; run < 10 && !mine().stream().allMatch(CrawledNotice::isMediaFetched); run++) {
            crawler.crawlSource(sources.findById(source.getId()).orElseThrow());
        }
        assertThat(mine()).allMatch(CrawledNotice::isMediaFetched);
        assertThat(jdbc.queryForObject("SELECT thumbnail_url FROM crawled_notices WHERE id = ?", String.class, id)).endsWith("/images/poster.jpg");
        assertThat(details.crawled(id, null).attachments()).hasSize(2);
        jdbc.update("UPDATE crawled_notices SET media_fetched = false WHERE id = ?", id);
        crawler.crawlSource(sources.findById(source.getId()).orElseThrow());
        assertThat(details.crawled(id, null).attachments()).hasSize(2);
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).isEmpty();

        // 재수집에 실패해도 이미 모은 첨부파일은 유지됨
        jdbc.update("UPDATE crawled_notices SET media_fetched = false WHERE id = ?", id);
        doThrow(new IOException("timeout")).when(fetcher).fetch(anyString());
        doReturn(load("catholic-list.html", LIST_URL)).when(fetcher).fetch(LIST_URL);
        crawler.crawlSource(sources.findById(source.getId()).orElseThrow());
        assertThat(details.crawled(id, null).attachments()).hasSize(2);

        // 실패한 공지는 하루가 지나기 전에는 다시 요청하지 않음
        doReturn(load("board-media-detail.html", LIST_URL)).when(fetcher).fetch(anyString());
        doReturn(load("catholic-list.html", LIST_URL)).when(fetcher).fetch(LIST_URL);
        crawler.crawlSource(sources.findById(source.getId()).orElseThrow());
        assertThat(mediaFetched(id)).isFalse();

        jdbc.update("UPDATE crawled_notices SET media_attempted_at = now() - interval '25 hours' WHERE id = ?", id);
        crawler.crawlSource(sources.findById(source.getId()).orElseThrow());
        assertThat(mediaFetched(id)).isTrue();
    }

    private boolean mediaFetched(long id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT media_fetched FROM crawled_notices WHERE id = ?", Boolean.class, id));
    }

    @Test
    void instagramGalleryIsNotChangedBySchoolMediaBackfill() throws IOException {
        CrawlSource instagram = new CrawlSource("instagram-regression-" + source.getId(), source.getOrganization(), "https://www.instagram.com/example/");
        instagram.disable();
        instagram = sources.save(instagram);
        CrawledNotice imported = new CrawledNotice(instagram, "post", "인스타 공지", "https://www.instagram.com/p/example/", false, java.time.Instant.now(), java.time.Instant.now());
        imported.applyContent(null, "인스타 본문");
        imported.assignImages(List.of("https://cdn.example/instagram.jpg"));
        long id = notices.saveAndFlush(imported).getId();
        stubSite();
        crawler.crawlSource(source);
        assertThat(sources.findAllEnabled()).extracting(CrawlSource::getCode).doesNotContain("instagram-regression-" + source.getId());
        assertThat(details.crawled(id, null).images()).containsExactly("https://cdn.example/instagram.jpg");
        assertThat(jdbc.queryForObject("SELECT thumbnail_url FROM crawled_notices WHERE id = ?", String.class, id)).isEqualTo("https://cdn.example/instagram.jpg");
    }

    private void stubSite() throws IOException {
        doReturn(load("catholic-detail.html", LIST_URL)).when(fetcher).fetch(anyString());
        doReturn(load("catholic-list.html", LIST_URL)).when(fetcher).fetch(LIST_URL);
    }

    private static Document load(String fixture, String baseUri) throws IOException {
        try (InputStream in = CrawlerServiceTest.class.getResourceAsStream("/fixtures/" + fixture)) {
            return Jsoup.parse(in, StandardCharsets.UTF_8.name(), baseUri);
        }
    }
}
