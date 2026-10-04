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
    @MockBean PageFetcher fetcher;

    private CrawlSource source;

    @BeforeEach
    void setUp() {
        notices.deleteAll();
        sources.deleteAll();
        organizations.deleteAll();
        Organization org = organizations.save(new Organization("가톨릭대학교 공지사항", OrganizationType.UNIVERSITY));
        source = sources.save(new CrawlSource("catholic_notice", org, LIST_URL));
    }

    @Test
    void firstCrawlBackfillsNoticesWithContentAndPublishesNoEvents() throws IOException {
        stubSite();

        CrawlResult result = crawler.crawlSource(source);

        assertThat(result.status()).isEqualTo(CrawlStatus.SUCCESS);
        assertThat(result.newNotices()).isEqualTo(4);
        List<CrawledNotice> saved = notices.findAll();
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
        assertThat(notices.count()).isEqualTo(4);
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).hasSize(1)
                .first().satisfies(e -> assertThat(e.title()).isNotBlank());
    }

    @Test
    void savesNoticeWithoutBodyWhenDetailFetchFailsAndFillsItLater() throws IOException {
        doThrow(new IOException("timeout")).when(fetcher).fetch(anyString());
        doReturn(load("catholic-list.html", LIST_URL)).when(fetcher).fetch(LIST_URL);

        crawler.crawlSource(source);
        assertThat(notices.findAll()).hasSize(4).noneMatch(CrawledNotice::isContentFetched);

        stubSite();
        crawler.crawlSource(sources.findById(source.getId()).orElseThrow());

        assertThat(notices.findAll()).hasSize(4).allMatch(CrawledNotice::isContentFetched);
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
