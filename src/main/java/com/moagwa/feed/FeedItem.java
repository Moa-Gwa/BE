package com.moagwa.feed;

import java.time.Instant;

public record FeedItem(
        String source,
        long id,
        Org organization,
        String title,
        String summary,
        String category,
        boolean urgent,
        boolean pinned,
        Instant publishedAt,
        Instant eventStartAt,
        Instant eventEndAt,
        Instant deadlineAt,
        String location,
        String status,
        int viewCount,
        String thumbnail,
        boolean bookmarked) {

    public record Org(long id, String name, String type) {
    }
}
