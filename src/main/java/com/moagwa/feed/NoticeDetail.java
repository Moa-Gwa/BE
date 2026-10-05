package com.moagwa.feed;

import com.moagwa.notice.NoticeBlock;
import java.time.Instant;
import java.util.List;

/** Detail payload for both sources. {@code contentHtml} is sanitized HTML for crawled notices, null for manual ones. */
public record NoticeDetail(
        String source,
        long id,
        FeedItem.Org organization,
        String title,
        String description,
        String contentHtml,
        List<String> categories,
        List<String> images,
        List<NoticeBlock> blocks,
        boolean urgent,
        Instant publishedAt,
        Instant updatedAt,
        Instant eventStartAt,
        Instant eventEndAt,
        Instant deadlineAt,
        String location,
        String externalLink,
        String status,
        int viewCount,
        int bookmarkCount,
        boolean bookmarked) {
}
