package com.moagwa.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawlStatus;
import com.moagwa.crawler.domain.CrawledNotice;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.crawler.fetch.PageFetcher;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.time.Instant;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/** 정기 수집 시 기존 공지의 미분류만 원문 분류로 채우고 기존 분류는 유지하는지 확인함. */
@SpringBootTest
class CrawlerCategoryTest {
    @Autowired CrawlSourceRepository sources;
    @Autowired CrawledNoticeRepository notices;
    @Autowired OrganizationRepository orgs;
    @Autowired JdbcTemplate jdbc;
    @Autowired CrawlerService crawler;
    @MockBean PageFetcher fetcher;
    CrawlSource source;
    final String url = "https://school.example/community/notice.do";

    long save(String external, String title, String category) {
        var n = new CrawledNotice(source, external, title, url + "?mode=view&articleNo=" + external, false, Instant.now(), Instant.now());
        n.assignCategory(category);
        return notices.saveAndFlush(n).getId();
    }
    String row(String id, String label) {
        return "<tr><td><a class='b-title' href='?mode=view&amp;articleNo=" + id + "'>공지 " + id + "</a>"
                + (label == null ? "" : "<span class='b-cate'>" + label + "</span>")
                + "<span class='b-date'>2026.11.15</span></td></tr>";
    }
    String category(long id) {
        return jdbc.queryForObject("SELECT category FROM crawled_notices WHERE id=?", String.class, id);
    }

    @Test void regularCrawlClassifiesNewNoticesAndBackfillsExistingWithoutOverwriting() throws Exception {
        var org = orgs.save(new Organization("분류검증-" + UUID.randomUUID(), OrganizationType.DEPARTMENT));
        source = sources.save(new CrawlSource("category-" + UUID.randomUUID(), org, url));
        long scholarship = save("101", "장학금 신청", null);
        long general = save("102", "장학금 제목이지만 원문 일반", null);
        long assigned = save("103", "기존 분류 유지", "WELFARE");
        long missing = save("105", "장학금 제목이지만 원문 분류 없음", null);
        String list = "<table><tbody>" + row("101", "장학") + row("102", "일반") + row("103", "장학")
                + row("104", "행사") + row("105", null) + "</tbody></table>";
        when(fetcher.fetch(anyString())).thenAnswer(call -> {
            String u = call.getArgument(0);
            return Jsoup.parse(u.equals(url) ? list : "<div class='b-con-box'>본문</div>", u);
        });

        assertThat(crawler.crawlSource(source).status()).isEqualTo(CrawlStatus.SUCCESS);
        assertThat(category(scholarship)).isEqualTo("SCHOLARSHIP");
        assertThat(category(assigned)).isEqualTo("WELFARE");
        assertThat(category(general)).isNull();
        assertThat(category(missing)).isNull();
        assertThat(notices.findBySourceAndExternalId(source.getId(), "104").orElseThrow().getCategory()).isEqualTo("EVENT");
    }
}
