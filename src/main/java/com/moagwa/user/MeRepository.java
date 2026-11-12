package com.moagwa.user;

import com.moagwa.user.MeDto.NotificationSettings;
import com.moagwa.user.MeDto.NotificationView;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 학생 본인의 관심사·저장·알림 테이블 접근. 모든 쿼리는 호출자가 넘긴 사용자 id로 한정됨. */
@Repository
@RequiredArgsConstructor
class MeRepository {

    private static final int NOTIFICATION_LIMIT = 50;

    private final NamedParameterJdbcTemplate jdbc;

    List<Long> interests(long userId, String kind) {
        return jdbc.queryForList(
                "SELECT target_id FROM user_interests WHERE user_id = :u AND kind = :k ORDER BY target_id",
                new MapSqlParameterSource().addValue("u", userId).addValue("k", kind), Long.class);
    }

    void deleteInterests(long userId) {
        jdbc.update("DELETE FROM user_interests WHERE user_id = :u", new MapSqlParameterSource("u", userId));
    }

    void insertInterests(long userId, String kind, List<Long> ids) {
        for (Long id : ids.stream().distinct().toList()) {
            jdbc.update("INSERT INTO user_interests (user_id, kind, target_id) VALUES (:u, :k, :t)",
                    new MapSqlParameterSource().addValue("u", userId).addValue("k", kind).addValue("t", id));
        }
    }

    int countOrganizations(List<Long> ids, String type) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM organizations WHERE id IN (:ids) AND type = :t",
                new MapSqlParameterSource().addValue("ids", ids).addValue("t", type), Integer.class);
        return found == null ? 0 : found;
    }

    int countCategories(List<Long> ids) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM categories WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids.isEmpty() ? List.of(-1L) : ids), Integer.class);
        return found == null ? 0 : found;
    }

    /** 이미 저장한 공지면 false */
    boolean insertBookmark(long userId, String source, long noticeId) {
        return jdbc.update("""
                INSERT INTO bookmarks (user_id, source, notice_id) VALUES (:u, :s, :n)
                ON CONFLICT DO NOTHING""", bookmark(userId, source, noticeId)) > 0;
    }

    /** 저장하지 않은 공지면 false */
    boolean deleteBookmark(long userId, String source, long noticeId) {
        return jdbc.update("DELETE FROM bookmarks WHERE user_id = :u AND source = :s AND notice_id = :n",
                bookmark(userId, source, noticeId)) > 0;
    }

    void addNoticeBookmarkCount(long noticeId, int delta) {
        jdbc.update("UPDATE notices SET bookmark_count = GREATEST(bookmark_count + :d, 0) WHERE id = :n",
                new MapSqlParameterSource().addValue("n", noticeId).addValue("d", delta));
    }

    List<NotificationView> recentNotifications(long userId) {
        return jdbc.query("""
                SELECT id, type, source, notice_id, title, body, read_at, created_at FROM notifications
                 WHERE user_id = :u ORDER BY created_at DESC, id DESC LIMIT :limit""",
                new MapSqlParameterSource().addValue("u", userId).addValue("limit", NOTIFICATION_LIMIT), (rs, i) -> {
                    Timestamp read = rs.getTimestamp("read_at");
                    return new NotificationView(rs.getLong("id"), rs.getString("type"), rs.getString("source"),
                            rs.getLong("notice_id"), rs.getString("title"), rs.getString("body"), read != null,
                            rs.getTimestamp("created_at").toInstant().toString());
                });
    }

    int countUnread(long userId) {
        Integer unread = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = :u AND read_at IS NULL",
                new MapSqlParameterSource("u", userId), Integer.class);
        return unread == null ? 0 : unread;
    }

    void markRead(long userId, long notificationId) {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE id = :id AND user_id = :u AND read_at IS NULL",
                new MapSqlParameterSource().addValue("id", notificationId).addValue("u", userId));
    }

    void markAllRead(long userId) {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE user_id = :u AND read_at IS NULL",
                new MapSqlParameterSource("u", userId));
    }

    Optional<NotificationSettings> findSettings(long userId) {
        return jdbc.query("""
                SELECT new_notice, event_start, deadline_soon, urgent FROM notification_settings WHERE user_id = :u""",
                new MapSqlParameterSource("u", userId),
                (rs, i) -> new NotificationSettings(rs.getBoolean("new_notice"), rs.getBoolean("event_start"),
                        rs.getBoolean("deadline_soon"), rs.getBoolean("urgent")))
                .stream().findFirst();
    }

    void saveSettings(long userId, NotificationSettings s) {
        jdbc.update("""
                INSERT INTO notification_settings (user_id, new_notice, event_start, deadline_soon, urgent)
                VALUES (:u, :a, :b, :c, :d)
                ON CONFLICT (user_id) DO UPDATE SET new_notice = :a, event_start = :b, deadline_soon = :c, urgent = :d""",
                new MapSqlParameterSource().addValue("u", userId)
                        .addValue("a", s.newNotice()).addValue("b", s.eventStart())
                        .addValue("c", s.deadlineSoon()).addValue("d", s.urgent()));
    }

    private static MapSqlParameterSource bookmark(long userId, String source, long noticeId) {
        return new MapSqlParameterSource().addValue("u", userId).addValue("s", source).addValue("n", noticeId);
    }
}
