package com.moagwa.crawler.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class BoardParserTest {

    private static final String LIST_URL = "https://www.catholic.ac.kr/ko/campuslife/notice.do";

    private final BoardParser parser = new BoardParser(new ContentSanitizer());
    private final CrawlSource source = new CrawlSource("catholic_notice",
            new Organization("가톨릭대학교 공지사항", OrganizationType.UNIVERSITY), LIST_URL);

    @Test
    void parsesListRowsWithCanonicalUrlDateAndPinnedFlag() throws IOException {
        List<ListItem> items = parser.parseList(load("catholic-list.html", LIST_URL), source);

        assertThat(items).extracting(ListItem::externalId)
                .containsExactly("276455", "275991", "276444", "276440");

        ListItem first = items.get(0);
        assertThat(first.title()).startsWith("[교수학습개발원] AI 활용 IT 응용프로그램 실습 7차");
        assertThat(first.url()).isEqualTo(LIST_URL + "?mode=view&articleNo=276455");
        assertThat(first.publishedDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(first.pinned()).isTrue();
        assertThat(items.get(2).pinned()).isFalse();
    }

    @Test
    void skipsRowsWithoutLinkAndDeduplicatesByArticleNumber() throws IOException {
        List<ListItem> items = parser.parseList(load("catholic-list.html", LIST_URL), source);

        // fixture has an empty-state row and the same article repeated (pinned + normal row)
        assertThat(items).hasSize(4);
        assertThat(items).extracting(ListItem::externalId).doesNotHaveDuplicates();
    }

    @Test
    void returnsEmptyWhenLayoutDoesNotMatch() {
        Document doc = Jsoup.parse("<html><body><p>점검 중입니다</p></body></html>", LIST_URL);

        assertThat(parser.parseList(doc, source)).isEmpty();
    }

    @Test
    void parsesDetailBodyIntoSanitizedHtmlAndPlainText() throws IOException {
        Optional<DetailContent> detail = parser.parseDetail(
                load("catholic-detail.html", LIST_URL + "?mode=view&articleNo=276455"), source);

        assertThat(detail).isPresent();
        String html = detail.get().html();
        assertThat(html).doesNotContain("<script", "onclick", "style=");
        assertThat(html).contains("https://www.catholic.ac.kr/_attach/cuk/editor-image/2026/10/");
        assertThat(detail.get().text())
                .contains("안녕하세요, 가톨릭대학교 학생 여러분.")
                .doesNotContain("<", "alert(1)");
    }

    @Test
    void returnsEmptyDetailWhenBodyElementIsMissing() {
        Document doc = Jsoup.parse("<html><body><div>없음</div></body></html>", LIST_URL);

        assertThat(parser.parseDetail(doc, source)).isEmpty();
    }

    private static Document load(String fixture, String baseUri) throws IOException {
        try (InputStream in = BoardParserTest.class.getResourceAsStream("/fixtures/" + fixture)) {
            return Jsoup.parse(in, StandardCharsets.UTF_8.name(), baseUri);
        }
    }
}
