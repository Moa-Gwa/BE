package com.moagwa.admin;

import com.moagwa.notice.NoticeBlock;
import java.time.Instant;
import java.util.List;

public record AdminNoticeView(
        long id,
        String title,
        String description,
        List<Long> categoryIds,
        List<String> categoryCodes,
        List<String> imageUrls,
        List<NoticeBlock> blocks,
        String status,
        String displayStatus,
        boolean urgent,
        Instant publishAt,
        Instant eventStartAt,
        Instant eventEndAt,
        Instant deadlineAt,
        String location,
        String externalLink,
        int viewCount,
        int bookmarkCount,
        int shareCount,
        Instant createdAt,
        Instant updatedAt) {
}
