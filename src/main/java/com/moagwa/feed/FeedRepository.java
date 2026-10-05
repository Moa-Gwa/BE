package com.moagwa.feed;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the unified v_feed_items view. All user input is bound as parameters. */
@Repository
@RequiredArgsConstructor
class FeedRepository {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final String SELECT = """
            SELECT f.*, (b.user_id IS NOT NULL) AS bookmarked
              FROM v_feed_items f
              LEFT JOIN bookmarks b ON b.user_id = CAST(:viewer AS bigint) AND b.source = f.source AND b.notice_id = f.id
            """;

    private static final RowMapper<FeedItem> MAPPER = FeedRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    FeedPage search(FeedFilter f) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("viewer", f.viewerId());
        List<String> where = new ArrayList<>();
        if (f.orgType() != null) {
            where.add("f.organization_type = :orgType");
            p.addValue("orgType", f.orgType());
        }
        if (f.organizationId() != null) {
            where.add("f.organization_id = :orgId");
            p.addValue("orgId", f.organizationId());
        }
        if (f.category() != null) {
            where.add("f.category = :category");
            p.addValue("category", f.category());
        }
        if (f.status() != null) {
            if (f.status().equals("CLOSED")) {
                where.add("f.display_status IN ('CLOSED', 'ENDED')");
            } else {
                where.add("f.display_status = :status");
                p.addValue("status", f.status());
            }
        }
        if (f.q() != null && !f.q().isBlank()) {
            where.add("(f.title ILIKE :q ESCAPE '\\' OR f.summary ILIKE :q ESCAPE '\\' "
                    + "OR f.organization_name ILIKE :q ESCAPE '\\')");
            p.addValue("q", "%" + escapeLike(f.q().trim()) + "%");
        }
        if (f.forUserId() != null) {
            where.add("""
                    (f.organization_id IN (
                        SELECT primary_department_id FROM users WHERE id = :me AND primary_department_id IS NOT NULL
                        UNION SELECT target_id FROM user_interests
                               WHERE user_id = :me AND kind IN ('DEPARTMENT', 'ORGANIZATION'))
                     OR f.category IN (
                        SELECT c.code FROM user_interests ui JOIN categories c ON c.id = ui.target_id
                         WHERE ui.user_id = :me AND ui.kind = 'CATEGORY'))
                    """);
            p.addValue("me", f.forUserId());
        }
        String sql = SELECT
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY " + orderBy(f.sort())
                + " LIMIT :limit OFFSET :offset";
        p.addValue("limit", f.size() + 1).addValue("offset", (long) f.page() * f.size());

        List<FeedItem> rows = jdbc.query(sql, p, MAPPER);
        boolean hasNext = rows.size() > f.size();
        return new FeedPage(hasNext ? rows.subList(0, f.size()) : rows, f.page(), f.size(), hasNext);
    }

    List<FeedItem> bookmarked(long userId, String status) {
        String sql = SELECT + " WHERE b.user_id IS NOT NULL"
                + (status == null ? "" : status.equals("CLOSED")
                        ? " AND f.display_status IN ('CLOSED', 'ENDED')" : " AND f.display_status = :status")
                + " ORDER BY b.created_at DESC";
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("viewer", userId);
        if (status != null && !status.equals("CLOSED")) {
            p.addValue("status", status);
        }
        return jdbc.query(sql, p, MAPPER);
    }

    /** Time-sensitive manual notices: urgent, ongoing, closing soon, today's events, then the coming week. */
    List<FeedItem> now(Long viewerId, int limit) {
        String sql = SELECT + """
                 WHERE f.source = 'MANUAL'
                   AND (f.display_status IS NULL OR f.display_status NOT IN ('CLOSED', 'ENDED'))
                   AND (f.is_urgent
                        OR f.display_status IN ('ONGOING', 'DEADLINE_SOON')
                        OR (f.event_start_at IS NOT NULL
                            AND f.event_start_at < now() + interval '7 days'))
                 ORDER BY CASE
                            WHEN f.is_urgent THEN 0
                            WHEN f.display_status = 'ONGOING' THEN 1
                            WHEN f.display_status = 'DEADLINE_SOON' THEN 2
                            WHEN (f.event_start_at AT TIME ZONE 'Asia/Seoul')::date
                                 = (now() AT TIME ZONE 'Asia/Seoul')::date THEN 3
                            ELSE 4 END,
                          COALESCE(f.deadline_at, f.event_start_at), f.published_at DESC
                 LIMIT :limit
                """;
        return jdbc.query(sql, new MapSqlParameterSource().addValue("viewer", viewerId).addValue("limit", limit),
                MAPPER);
    }

    List<ScheduleEntry> schedule(LocalDate from, LocalDate toExclusive) {
        String sql = """
                SELECT * FROM (
                    SELECT 'EVENT' AS type, f.event_start_at AS at, f.source, f.id, f.title,
                           f.organization_name, f.location
                      FROM v_feed_items f WHERE f.source = 'MANUAL' AND f.event_start_at IS NOT NULL
                    UNION ALL
                    SELECT 'DEADLINE', f.deadline_at, f.source, f.id, f.title, f.organization_name, f.location
                      FROM v_feed_items f WHERE f.source = 'MANUAL' AND f.deadline_at IS NOT NULL
                ) s
                WHERE s.at >= :from AND s.at < :to
                ORDER BY s.at
                """;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(from.atStartOfDay(KST).toInstant()))
                .addValue("to", Timestamp.from(toExclusive.atStartOfDay(KST).toInstant()));
        return jdbc.query(sql, p, (rs, i) -> new ScheduleEntry(
                rs.getString("type"), rs.getTimestamp("at").toInstant(), rs.getString("source"),
                rs.getLong("id"), rs.getString("title"), rs.getString("organization_name"),
                rs.getString("location")));
    }

    private static String orderBy(String sort) {
        if (sort == null) {
            sort = "latest";
        }
        return switch (sort) {
            case "deadline" -> "(CASE WHEN f.deadline_at >= now() THEN 0 ELSE 1 END), f.deadline_at ASC NULLS LAST, "
                    + "f.published_at DESC";
            case "event" -> "(CASE WHEN f.event_start_at >= now() THEN 0 ELSE 1 END), f.event_start_at ASC NULLS LAST, "
                    + "f.published_at DESC";
            case "popular" -> "f.view_count DESC, f.published_at DESC, f.id DESC";
            default -> "f.published_at DESC, f.source, f.id DESC";
        };
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static FeedItem map(ResultSet rs, int row) throws SQLException {
        return new FeedItem(
                rs.getString("source"),
                rs.getLong("id"),
                new FeedItem.Org(rs.getLong("organization_id"), rs.getString("organization_name"),
                        rs.getString("organization_type")),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("category"),
                rs.getBoolean("is_urgent"),
                rs.getBoolean("pinned"),
                instant(rs, "published_at"),
                instant(rs, "event_start_at"),
                instant(rs, "event_end_at"),
                instant(rs, "deadline_at"),
                rs.getString("location"),
                rs.getString("display_status"),
                rs.getInt("view_count"),
                rs.getString("thumbnail"),
                rs.getBoolean("bookmarked"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
