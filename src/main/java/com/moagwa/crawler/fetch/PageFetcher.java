package com.moagwa.crawler.fetch;

import java.io.IOException;
import org.jsoup.nodes.Document;

public interface PageFetcher {

    Document fetch(String url) throws IOException;
}
