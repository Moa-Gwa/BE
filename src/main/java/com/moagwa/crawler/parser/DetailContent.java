package com.moagwa.crawler.parser;

import com.moagwa.crawler.domain.CrawledAttachment;
import java.util.List;

public record DetailContent(String html, String text, String thumbnailUrl, List<CrawledAttachment> attachments) {
}
