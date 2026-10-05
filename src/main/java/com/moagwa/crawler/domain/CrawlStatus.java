package com.moagwa.crawler.domain;

public enum CrawlStatus {
    SUCCESS,
    FAILED,
    /** The list page was fetched but no rows could be parsed (site layout may have changed). */
    EMPTY
}
