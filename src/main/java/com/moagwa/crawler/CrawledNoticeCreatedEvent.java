package com.moagwa.crawler;

/** Published after a newly crawled notice is committed (not for a source's first backfill crawl). */
public record CrawledNoticeCreatedEvent(Long noticeId, Long sourceId, Long organizationId, String title) {
}
