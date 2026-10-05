package com.moagwa.crawler.fetch;

import com.moagwa.crawler.CrawlerProperties;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JsoupPageFetcher implements PageFetcher {

    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; MoaGwaBot/1.0; +https://github.com/Moa-Gwa)";

    private final CrawlerProperties properties;

    @Override
    public Document fetch(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout((int) properties.requestTimeout().toMillis())
                .get();
    }
}
