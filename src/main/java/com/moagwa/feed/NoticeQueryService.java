package com.moagwa.feed;

import com.moagwa.common.exception.ApiException;
import com.moagwa.crawler.domain.CrawledAttachment;
import com.moagwa.notice.Category;
import com.moagwa.notice.DisplayStatus;
import com.moagwa.notice.Notice;
import com.moagwa.notice.NoticeBlockCodec;
import com.moagwa.notice.NoticeImage;
import com.moagwa.notice.NoticeRepository;
import com.moagwa.notice.NoticeStatus;
import com.moagwa.notice.NoticeStatusCalculator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NoticeQueryService {

    private final NoticeRepository notices;
    private final NoticeBlockCodec blockCodec;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public NoticeDetail manual(long id, Long viewerId) {
        Notice n = notices.findActive(id).filter(Notice::isVisibleToStudents)
                .orElseThrow(() -> ApiException.notFound("공지를 찾을 수 없습니다."));
        DisplayStatus status = n.displayStatus(Instant.now());
        return new NoticeDetail("MANUAL", n.getId(),
                new FeedItem.Org(n.getOrganization().getId(), n.getOrganization().getName(),
                        n.getOrganization().getType().name()),
                n.getTitle(), n.getDescription(), null,
                n.getCategories().stream().map(Category::getCode).sorted().toList(),
                n.getImages().stream().map(NoticeImage::getUrl).toList(),
                blockCodec.fromJson(n.getContentBlocks()), n.isUrgent(), n.getPublishAt(), n.getUpdatedAt(), n.getEventStartAt(), n.getEventEndAt(),
                n.getDeadlineAt(), n.getLocation(), n.getExternalLink(),
                status == null ? null : status.name(), n.getViewCount(), n.getBookmarkCount(),
                isBookmarked(viewerId, "MANUAL", id), null, List.of(), List.of());
    }

    @Transactional(readOnly = true)
    public NoticeDetail crawled(long id, Long viewerId) {
        List<NoticeDetail> rows = jdbc.query("""
                SELECT c.id, c.title, c.content_html, c.content_text, c.url, c.published_at, c.view_count, c.category,
                       c.deadline_at, c.event_start_at, c.event_end_at, c.date_evidence, c.keywords,
                       o.id AS org_id, o.name AS org_name, o.type AS org_type
                  FROM crawled_notices c
                  JOIN crawl_sources s ON s.id = c.source_id
                  JOIN organizations o ON o.id = s.organization_id
                 WHERE c.id = :id
                """, new MapSqlParameterSource("id", id), (rs, i) -> {
            Timestamp published = rs.getTimestamp("published_at");
            String category = rs.getString("category");
            Instant deadline = instant(rs.getTimestamp("deadline_at"));
            Instant eventStart = instant(rs.getTimestamp("event_start_at"));
            Instant eventEnd = instant(rs.getTimestamp("event_end_at"));
            DisplayStatus status = NoticeStatusCalculator.display(NoticeStatus.PUBLISHED, eventStart, eventEnd,
                    deadline, Instant.now());
            return new NoticeDetail("CRAWLED", rs.getLong("id"),
                    new FeedItem.Org(rs.getLong("org_id"), rs.getString("org_name"), rs.getString("org_type")),
                    rs.getString("title"), rs.getString("content_text"), rs.getString("content_html"),
                    category == null ? List.of() : List.of(category), crawledImages(id), null, false,
                    published.toInstant(), published.toInstant(), eventStart, eventEnd, deadline, null,
                    rs.getString("url"), status == null ? null : status.name(), rs.getInt("view_count"), 0, false,
                    rs.getString("date_evidence"), List.of((String[]) rs.getArray("keywords").getArray()), crawledAttachments(id));
        });
        if (rows.isEmpty()) {
            throw ApiException.notFound("공지를 찾을 수 없습니다.");
        }
        NoticeDetail d = rows.get(0);
        return new NoticeDetail(d.source(), d.id(), d.organization(), d.title(), d.description(), d.contentHtml(),
                d.categories(), d.images(), d.blocks(), d.urgent(), d.publishedAt(), d.updatedAt(), d.eventStartAt(),
                d.eventEndAt(), d.deadlineAt(), d.location(), d.externalLink(), d.status(), d.viewCount(),
                d.bookmarkCount(), isBookmarked(viewerId, "CRAWLED", id), d.dateEvidence(), d.keywords(), d.attachments());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private List<String> crawledImages(long id) {
        return jdbc.queryForList("SELECT url FROM crawled_notice_images WHERE notice_id = :id ORDER BY sort_order",
                new MapSqlParameterSource("id", id), String.class);
    }

    private List<CrawledAttachment> crawledAttachments(long id) {
        return jdbc.query("SELECT file_name, url FROM crawled_notice_attachments WHERE notice_id = :id ORDER BY sort_order",
                new MapSqlParameterSource("id", id), (rs, i) -> new CrawledAttachment(rs.getString("file_name"), rs.getString("url")));
    }

    /** Count each student once per rolling 24 hours; record and counter commit atomically. */
    @Transactional
    public void increaseView(String source, long id, Long userId) {
        if (userId == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        if ("MANUAL".equals(source)) {
            notices.findActive(id).filter(Notice::isVisibleToStudents)
                    .orElseThrow(() -> ApiException.notFound("공지를 찾을 수 없습니다."));
        } else if ("CRAWLED".equals(source)) {
            Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM crawled_notices WHERE id = :id)",
                    new MapSqlParameterSource("id", id), Boolean.class);
            if (!Boolean.TRUE.equals(exists)) throw ApiException.notFound("공지를 찾을 수 없습니다.");
        } else {
            throw ApiException.badRequest("지원하지 않는 공지 출처입니다.");
        }
        MapSqlParameterSource params = new MapSqlParameterSource("id", id)
                .addValue("user", userId).addValue("source", source);
        int counted = jdbc.update("""
                INSERT INTO notice_view_records(user_id, source, notice_id, viewed_at)
                VALUES (:user, :source, :id, statement_timestamp())
                ON CONFLICT (user_id, source, notice_id) DO UPDATE
                SET viewed_at = EXCLUDED.viewed_at
                WHERE notice_view_records.viewed_at <= EXCLUDED.viewed_at - interval '24 hours'
                """, params);
        if (counted == 0) return;
        int updated = "MANUAL".equals(source) ? notices.incrementViews(id)
                : jdbc.update("UPDATE crawled_notices SET view_count = view_count + 1 WHERE id = :id", params);
        if (updated != 1) throw ApiException.notFound("공지를 찾을 수 없습니다.");
    }

    private boolean isBookmarked(Long viewerId, String source, long id) {
        if (viewerId == null) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM bookmarks WHERE user_id = :u AND source = :s AND notice_id = :n",
                new MapSqlParameterSource().addValue("u", viewerId).addValue("s", source).addValue("n", id),
                Integer.class);
        return n != null && n > 0;
    }
}
