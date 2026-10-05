package com.moagwa.feed;

import com.moagwa.common.exception.ApiException;
import com.moagwa.notice.Category;
import com.moagwa.notice.DisplayStatus;
import com.moagwa.notice.Notice;
import com.moagwa.notice.NoticeImage;
import com.moagwa.notice.NoticeRepository;
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
                n.isUrgent(), n.getPublishAt(), n.getUpdatedAt(), n.getEventStartAt(), n.getEventEndAt(),
                n.getDeadlineAt(), n.getLocation(), n.getExternalLink(),
                status == null ? null : status.name(), n.getViewCount(), n.getBookmarkCount(),
                isBookmarked(viewerId, "MANUAL", id));
    }

    @Transactional(readOnly = true)
    public NoticeDetail crawled(long id, Long viewerId) {
        List<NoticeDetail> rows = jdbc.query("""
                SELECT c.id, c.title, c.content_html, c.content_text, c.url, c.published_at, c.view_count, c.category,
                       o.id AS org_id, o.name AS org_name, o.type AS org_type
                  FROM crawled_notices c
                  JOIN crawl_sources s ON s.id = c.source_id
                  JOIN organizations o ON o.id = s.organization_id
                 WHERE c.id = :id
                """, new MapSqlParameterSource("id", id), (rs, i) -> {
            Timestamp published = rs.getTimestamp("published_at");
            String category = rs.getString("category");
            return new NoticeDetail("CRAWLED", rs.getLong("id"),
                    new FeedItem.Org(rs.getLong("org_id"), rs.getString("org_name"), rs.getString("org_type")),
                    rs.getString("title"), rs.getString("content_text"), rs.getString("content_html"),
                    category == null ? List.of() : List.of(category), List.of(), false,
                    published.toInstant(), published.toInstant(), null, null, null, null, rs.getString("url"),
                    null, rs.getInt("view_count"), 0, false);
        });
        if (rows.isEmpty()) {
            throw ApiException.notFound("공지를 찾을 수 없습니다.");
        }
        NoticeDetail d = rows.get(0);
        return new NoticeDetail(d.source(), d.id(), d.organization(), d.title(), d.description(), d.contentHtml(),
                d.categories(), d.images(), d.urgent(), d.publishedAt(), d.updatedAt(), d.eventStartAt(),
                d.eventEndAt(), d.deadlineAt(), d.location(), d.externalLink(), d.status(), d.viewCount(),
                d.bookmarkCount(), isBookmarked(viewerId, "CRAWLED", id));
    }

    @Transactional
    public void increaseView(String source, long id) {
        if (source.equals("MANUAL")) {
            notices.incrementViews(id);
        } else {
            jdbc.update("UPDATE crawled_notices SET view_count = view_count + 1 WHERE id = :id",
                    new MapSqlParameterSource("id", id));
        }
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
