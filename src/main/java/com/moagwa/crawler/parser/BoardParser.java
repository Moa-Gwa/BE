package com.moagwa.crawler.parser;

import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawledAttachment;
import java.net.URI;
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
        return Optional.of(new DetailContent(html, toPlainText(html), thumbnail(body, doc.location()), attachments(doc, source)));
    }

    private static String httpUrl(String base, String value) {
        if (value.isBlank()) return null;
        Element link = new Element("a");
        link.setBaseUri(base);
        link.attr("href", value.trim());
        String resolved = link.absUrl("href");
        try {
            URI uri = URI.create(resolved);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null ? resolved : null;
        } catch (IllegalArgumentException e) { return null; }
    }

    private static String thumbnail(Element body, String base) {
        for (Element img : body.select("img")) {
            String raw = !img.attr("data-src").isBlank() ? img.attr("data-src") : img.attr("src");
            String url = httpUrl(base, raw);
            if (url == null || url.length() > 500 || hidden(img) || smallImage(img)) continue;
            String path = URI.create(url).getPath();
            if (path != null && path.toLowerCase(java.util.Locale.ROOT)
                    .matches(".*(?:^|[/_.-])(?:icon|ico|logo|blank|spacer|transparent|pixel)(?:[/_.0-9-]|$).*")) continue;
            return url;
        }
        return null;
    }

    private static boolean hidden(Element img) {
        for (Element e = img; e != null; e = e.parent()) {
            if (e.hasAttr("hidden") || "true".equalsIgnoreCase(e.attr("aria-hidden"))
                    || e.attr("style").toLowerCase(java.util.Locale.ROOT).matches(".*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden).*")) return true;
        }
        return false;
    }

    private static boolean smallImage(Element img) {
        for (String attr : List.of("width", "height", "data-width", "data-height")) {
            String value = img.attr(attr).trim();
            if (value.matches("[0-9]+(?:px)?")) {
                try { if (Integer.parseInt(value.replace("px", "")) <= 32) return true; }
                catch (NumberFormatException ignored) { /* Invalid dimensions do not identify a tracking pixel. */ }
            }
        }
        return Pattern.compile("(?:width|height)\\s*:\\s*([0-9]+)px", Pattern.CASE_INSENSITIVE)
                .matcher(img.attr("style")).results().anyMatch(m -> {
                    try { return Integer.parseInt(m.group(1)) <= 32; }
                    catch (NumberFormatException e) { return false; }
                });
    }

    private static List<CrawledAttachment> attachments(Document doc, CrawlSource source) {
        Map<String, CrawledAttachment> files = new LinkedHashMap<>();
        for (Element link : doc.select(source.getAttachmentSelector())) {
            String url = httpUrl(doc.location(), link.attr("href"));
            if (url == null) continue;
            Element label = link.clone();
            label.select(".hide, .sr-only, .file-size, .file_size").remove();
            String name = normalize(label.text());
            if (name.isBlank()) name = normalize(link.attr("download"));
            if (name.isBlank()) name = normalize(link.attr("title"));
            if (name.isBlank() || name.equals("다운로드")) name = "첨부파일";
            files.putIfAbsent(url, new CrawledAttachment(name, url));
        }
        return List.copyOf(files.values());
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
