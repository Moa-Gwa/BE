package com.moagwa.crawler.parser;

import com.moagwa.crawler.domain.CrawlSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Parses the shared university CMS board layout (list page and article page).
 * Selectors come from {@link CrawlSource} so a site with a different layout only needs different config.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoardParser {

    private static final Pattern ARTICLE_NO = Pattern.compile("[?&]articleNo=(\\d+)");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final String NEWLINE_MARK = "\u0001";

    private final ContentSanitizer sanitizer;

    /**
     * Parses every row of the list page (pinned notices included). Rows without a link or article number are
     * skipped; the result is de-duplicated by article number and keeps page order.
     */
    public List<ListItem> parseList(Document doc, CrawlSource source) {
        Map<String, ListItem> items = new LinkedHashMap<>();
        for (Element row : doc.select(source.getRowSelector())) {
            Element link = row.selectFirst(source.getLinkSelector());
            if (link == null) {
                continue;
            }
            String articleNo = extractArticleNo(link);
            String title = normalize(link.text());
            if (articleNo == null || title.isEmpty()) {
                log.warn("[{}] skipping row without article number or title: {}", source.getCode(), title);
                continue;
            }
            items.putIfAbsent(articleNo, new ListItem(
                    articleNo,
                    title,
                    articleUrl(source.getListUrl(), articleNo),
                    parseDate(row.selectFirst(source.getDateSelector())),
                    row.hasClass("b-cate-notice"),
                    boardCategory(row)));
        }
        return new ArrayList<>(items.values());
    }

    /** The board's category label shown in the row (".b-cate"), when the board has one. */
    private static String boardCategory(Element row) {
        Element cate = row.selectFirst(".b-cate");
        if (cate == null) {
            return null;
        }
        String label = normalize(cate.text());
        return label.isEmpty() ? null : label;
    }

    /** Returns the sanitized article body, or empty when the body element is missing. */
    public Optional<DetailContent> parseDetail(Document doc, CrawlSource source) {
        Element body = doc.selectFirst(source.getBodySelector());
        if (body == null) {
            return Optional.empty();
        }
        String html = sanitizer.clean(body.html(), doc.location());
        return Optional.of(new DetailContent(html, toPlainText(html)));
    }

    static String articleUrl(String listUrl, String articleNo) {
        int cut = listUrl.indexOf('?');
        String base = cut >= 0 ? listUrl.substring(0, cut) : listUrl;
        return base + "?mode=view&articleNo=" + articleNo;
    }

    private static String extractArticleNo(Element link) {
        String attr = link.attr("data-article-no");
        if (!attr.isBlank()) {
            return attr.trim();
        }
        Matcher m = ARTICLE_NO.matcher(link.attr("href"));
        return m.find() ? m.group(1) : null;
    }

    private static LocalDate parseDate(Element el) {
        if (el == null) {
            return null;
        }
        try {
            return LocalDate.parse(el.text().trim(), DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String normalize(String s) {
        return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String toPlainText(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        doc.select("br").after(NEWLINE_MARK);
        doc.select("p, div, li, tr, h1, h2, h3, h4, h5, h6").append(NEWLINE_MARK);
        return doc.body().text().replace(' ', ' ')
                .replaceAll(" ?" + NEWLINE_MARK + " ?", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
