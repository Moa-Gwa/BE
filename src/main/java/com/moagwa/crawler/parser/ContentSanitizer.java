package com.moagwa.crawler.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/** Cleans crawled HTML so it is safe to render: no scripts/styles/handlers, absolute links, external links hardened. */
@Component
public class ContentSanitizer {

    private final Safelist safelist = Safelist.relaxed()
            .addTags("figure", "figcaption")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")
            .addEnforcedAttribute("a", "target", "_blank");

    public String clean(String html, String baseUri) {
        Document.OutputSettings output = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(html, baseUri, safelist, output).trim();
    }
}
