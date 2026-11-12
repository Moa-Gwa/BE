package com.moagwa.feed;

import com.moagwa.notice.NoticeBlock;
import java.time.Instant;
import java.util.List;

/**
 * Detail payload for both sources. {@code contentHtml} is sanitized HTML for crawled notices, null for manual ones.
 * {@code dateEvidence}·{@code keywords}는 크롤링 공지 본문에서 추출한 값(마감·행사일의 근거 문장, 검색 키워드)
 */
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
        boolean bookmarked,
        String dateEvidence,
        List<String> keywords,
        List<Attachment> attachments) {

    /** 원문 사이트의 첨부파일. url은 원문 서버의 다운로드 주소 */
    public record Attachment(String fileName, String url) {
    }
}
