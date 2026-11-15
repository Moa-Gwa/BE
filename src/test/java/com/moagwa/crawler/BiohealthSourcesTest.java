package com.moagwa.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.parser.BoardParser;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"crawler.enabled=false", "crawler.instagram.enabled=false"})
@Transactional
class BiohealthSourcesTest {
    @Autowired CrawlSourceRepository sources;
    @Autowired BoardParser parser;

    @ParameterizedTest
    @CsvSource({
        "dept_ai_biomedical,AI의공학과,https://aibme.catholic.ac.kr/aibme/community/notice.do,aibme-list.html,277678",
        "dept_biologics,바이오로직스공학부,https://ble.catholic.ac.kr/BLE/community/notice.do,ble-list.html,277679"
    })
    void migrationRegistersEnabledDepartmentsParsableWithDefaultSelectors(
            String code, String name, String url, String fixture, String firstArticle) throws Exception {
        var source = sources.findByCode(code).orElseThrow();
        assertThat(source.isEnabled()).isTrue();
        assertThat(source.isFirstCrawl()).isTrue();
        assertThat(source.getListUrl()).isEqualTo(url);
        assertThat(source.getOrganization().getName()).isEqualTo(name);
        assertThat(source.getOrganization().getCollege()).isEqualTo("바이오헬스융합대학");
        assertThat(source.getOrganization().getType().name()).isEqualTo("DEPARTMENT");
        assertThat(source.getOrganization().isVerified()).isTrue();
        try (var input = getClass().getResourceAsStream("/fixtures/" + fixture)) {
            var doc = Jsoup.parse(input, StandardCharsets.UTF_8.name(), url);
            var items = parser.parseList(doc, source);
            assertThat(items).isNotEmpty();
            assertThat(items.get(0).externalId()).isEqualTo(firstArticle);
            assertThat(items.get(0).url()).isEqualTo(url + "?mode=view&articleNo=" + firstArticle);
            assertThat(items.get(0).title()).isNotBlank();
            assertThat(items.get(0).publishedDate()).isNotNull();
        }
    }
}
