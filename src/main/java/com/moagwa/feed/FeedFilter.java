package com.moagwa.feed;

/** Query options for the student feed. {@code forUserId} limits results to that student's interests. */
public record FeedFilter(
        String orgType,
        Long organizationId,
        String category,
        String status,
        String q,
        String sort,
        Long forUserId,
        Long viewerId,
        int page,
        int size) {

    public static final int MAX_SIZE = 50;

    public FeedFilter {
        page = Math.max(page, 0);
        size = size <= 0 ? 20 : Math.min(size, MAX_SIZE);
    }
}
