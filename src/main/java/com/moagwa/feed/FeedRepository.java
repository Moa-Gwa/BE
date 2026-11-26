package com.moagwa.feed;

import com.moagwa.common.exception.ApiException;
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

    /**
     * 학생(:me)의 주전공, 관심 학과/단체, 필수 기관 공지와 관심 카테고리의 학교 전체(대학) 공지.
     * 카테고리는 학교 공지에만 적용해 관심 설정하지 않은 학과나 단체 글이 섞이지 않게 함
     */
    private static final String INTEREST = """
            (f.organization_id IN (
                SELECT primary_department_id FROM users WHERE id = :me AND primary_department_id IS NOT NULL
                UNION SELECT target_id FROM user_interests
                       WHERE user_id = :me AND kind IN ('DEPARTMENT', 'ORGANIZATION')
                UNION SELECT id FROM organizations WHERE is_required)
             OR f.organization_type = 'UNIVERSITY' AND f.category IN (
                SELECT c.code FROM user_interests ui JOIN categories c ON c.id = ui.target_id
                 WHERE ui.user_id = :me AND ui.kind = 'CATEGORY'))
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
        String match = switch (f.field() == null ? "all" : f.field()) {
            case "title" -> "f.title ILIKE :q ESCAPE '\\'";
            case "content" -> "f.body ILIKE :q ESCAPE '\\'";
            case "all" -> "(f.title ILIKE :q ESCAPE '\\' OR f.body ILIKE :q ESCAPE '\\' "
                    + "OR f.organization_name ILIKE :q ESCAPE '\\' OR f.keywords ILIKE :q ESCAPE '\\')";
            default -> throw ApiException.badRequest("검색 범위는 all, title, content 중 하나여야 합니다.");
        };
        if (f.q() != null && !f.q().isBlank()) {
            where.add(match);
            p.addValue("q", "%" + escapeLike(f.q().trim()) + "%");
        }
        if (f.forUserId() != null) {
            where.add(INTEREST);
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

    /**
     * 학내 NOW. 긴급 공지는 모두에게, 진행 중/마감 임박/7일 안 행사는 로그인 학생의 관심 공지만 보여줌.
     * 비로그인은 긴급 공지만.
     */
    List<FeedItem> now(Long viewerId, int limit) {
        String sql = SELECT + """
                 WHERE (f.display_status IS NULL OR f.display_status NOT IN ('CLOSED', 'ENDED'))
                   AND ((f.source = 'MANUAL' AND f.is_urgent)
                        OR (CAST(:me AS bigint) IS NOT NULL
                            AND (f.display_status = 'DEADLINE_SOON'
                                 -- 크롤링 공지는 종료일이 없으면 시작 뒤 계속 ONGOING이라 종료일이 있는 것만
                                 OR (f.display_status = 'ONGOING' AND (f.source = 'MANUAL' OR f.event_end_at IS NOT NULL))
                                 OR (f.event_start_at >= now() AND f.event_start_at < now() + interval '7 days'))
                            AND """ + INTEREST + """
                        ))
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
        return jdbc.query(sql, new MapSqlParameterSource().addValue("viewer", viewerId)
                .addValue("me", viewerId, java.sql.Types.BIGINT).addValue("limit", limit), MAPPER);
    }

    /** 단체가 직접 올린 공지 전체와, 로그인한 학생이 저장한 공지(출처 무관)의 마감·행사 일정 */
    List<ScheduleEntry> schedule(LocalDate from, LocalDate toExclusive, Long viewerId) {
        String sql = """
                WITH items AS (
                    SELECT f.*, (b.user_id IS NOT NULL) AS bookmarked
                      FROM v_feed_items f
                      LEFT JOIN bookmarks b
                        ON b.user_id = CAST(:viewer AS bigint) AND b.source = f.source AND b.notice_id = f.id
                     WHERE f.source = 'MANUAL' OR b.user_id IS NOT NULL
                )
                SELECT s.* FROM (
                    SELECT 'EVENT' AS type, i.event_start_at AS at, i.event_end_at AS end_at, i.source, i.id, i.title,
                           i.organization_name, i.location, i.bookmarked, CAST(NULL AS text) AS memo
                      FROM items i WHERE i.event_start_at IS NOT NULL
                    UNION ALL
                    SELECT 'DEADLINE', i.deadline_at, NULL, i.source, i.id, i.title, i.organization_name, i.location,
                           i.bookmarked, NULL
                      FROM items i WHERE i.deadline_at IS NOT NULL
                    UNION ALL
                    SELECT 'PERSONAL', starts_at, ends_at, 'PERSONAL', id, title, '내 일정', location, false, memo
                      FROM personal_schedules WHERE user_id = CAST(:viewer AS bigint)
                ) s
                WHERE s.at < :to AND (s.at >= :from OR s.end_at > :from)
                ORDER BY s.at
                """;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("viewer", viewerId)
                .addValue("from", Timestamp.from(from.atStartOfDay(KST).toInstant()))
                .addValue("to", Timestamp.from(toExclusive.atStartOfDay(KST).toInstant()));
        return jdbc.query(sql, p, (rs, i) -> new ScheduleEntry(
                rs.getString("type"), rs.getTimestamp("at").toInstant(), rs.getString("source"),
                rs.getLong("id"), rs.getString("title"), rs.getString("organization_name"),
                rs.getString("location"), rs.getBoolean("bookmarked"), rs.getString("memo"), null,
                rs.getTimestamp("end_at") == null ? null : rs.getTimestamp("end_at").toInstant()));
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
            // 게시판 공지는 시각 없이 날짜만 있어 같은 날 글이 한 게시판씩 몰림. 게시일이 같으면 기관 종류를 번갈아 섞음
            default -> "f.published_at DESC, "
                    + "row_number() OVER (PARTITION BY f.published_at, f.organization_type ORDER BY f.source, f.id DESC), "
                    + "f.organization_type, f.source, f.id DESC";
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
