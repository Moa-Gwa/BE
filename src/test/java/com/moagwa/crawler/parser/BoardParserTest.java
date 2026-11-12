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
        assertThat(first.category()).isEqualTo("일반");
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

    @Test
    void extractsFirstRealImageAndAttachmentNamesAndCanonicalDownloadUrls() throws IOException {
        String url = "https://school.example/board/notice.do?mode=view&articleNo=123";
        DetailContent content = parser.parseDetail(load("board-media-detail.html", url), source).orElseThrow();
        assertThat(content.thumbnailUrl()).isEqualTo("https://school.example/images/poster.jpg");
        assertThat(content.attachments()).extracting(com.moagwa.crawler.domain.CrawledAttachment::getFileName)
                .containsExactly("모집 안내.pdf", "신청서.hwpx");
        assertThat(content.attachments()).extracting(com.moagwa.crawler.domain.CrawledAttachment::getUrl)
                .containsExactly("https://school.example/board/notice.do?mode=download&articleNo=123&attachNo=456", "https://school.example/files/form.hwpx");
    }

    @Test
    void textOnlyNoticeHasNoThumbnailOrAttachments() {
        DetailContent content = parser.parseDetail(Jsoup.parse("<div class='b-con-box'>이미지 없는 공지</div>", LIST_URL), source).orElseThrow();
        assertThat(content.thumbnailUrl()).isNull();
        assertThat(content.attachments()).isEmpty();
    }

    @Test
    void rejectsIconsHiddenImagesSmallInlineDimensionsAndUnsafeUrls() {
        String html = "<div class='b-con-box'><img src='/spacer.gif'><img src='/normal.jpg' style='width: 1px'>"
                + "<img src='/hidden.jpg' style='display: none'><img src='javascript:alert(1)'></div>";
        assertThat(parser.parseDetail(Jsoup.parse(html, LIST_URL), source).orElseThrow().thumbnailUrl()).isNull();
    }

    @Test
    void attachmentSelectorCanBeCustomizedWithoutChangingTheParser() {
        org.springframework.test.util.ReflectionTestUtils.setField(source, "attachmentSelector", ".downloads a[href]");
        DetailContent content = parser.parseDetail(Jsoup.parse("<div class='b-con-box'>공지</div><div class='downloads'><a href='/file.zip' download='자료.zip'></a></div>", LIST_URL), source).orElseThrow();
        assertThat(content.attachments()).extracting(com.moagwa.crawler.domain.CrawledAttachment::getFileName).containsExactly("자료.zip");
    }

    private static Document load(String fixture, String baseUri) throws IOException {
        try (InputStream in = BoardParserTest.class.getResourceAsStream("/fixtures/" + fixture)) {
            return Jsoup.parse(in, StandardCharsets.UTF_8.name(), baseUri);
        }
    }
}
