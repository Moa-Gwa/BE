package com.moagwa.crawler;

import com.moagwa.crawler.domain.CrawlStatus;

public record CrawlResult(String sourceCode, CrawlStatus status, int newNotices, String error) {

    public boolean isFailed() {
        return status == CrawlStatus.FAILED;
    }
}
