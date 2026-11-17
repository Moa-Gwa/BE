package com.moagwa.user;

import com.moagwa.organization.Organization;
import com.moagwa.user.MeDto.NotificationSettings;
import com.moagwa.user.MeDto.NotificationView;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** 학과 id별 학과 학생회 id. 학과가 아니거나 학생회가 아직 없으면 결과에 없음 */
    Map<Long, Long> studentGovernmentsOf(List<Long> departmentIds) {
        if (departmentIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> departmentByCouncilName = new HashMap<>();
        jdbc.query("SELECT id, name FROM organizations WHERE id IN (:ids) AND type = 'DEPARTMENT'",
                new MapSqlParameterSource("ids", departmentIds),
                rs -> { departmentByCouncilName.put(Organization.councilNameOf(rs.getString("name")), rs.getLong("id")); });
        Map<Long, Long> councils = new HashMap<>();
        if (!departmentByCouncilName.isEmpty()) {
            jdbc.query("SELECT id, name FROM organizations WHERE name IN (:names) AND type = 'STUDENT_GOVERNMENT'",
                    new MapSqlParameterSource("names", departmentByCouncilName.keySet()),
                    rs -> { councils.put(departmentByCouncilName.get(rs.getString("name")), rs.getLong("id")); });
        }
        return councils;
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
                 WHERE user_id = :u AND (read_at IS NULL OR read_at > now() - INTERVAL '14 days')
                 ORDER BY created_at DESC, id DESC LIMIT :limit""",
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

    /** 다른 학생과 진행 중인 거래가 있으면 탈퇴하지 못함. 지우면 상대의 거래, 결제, 채팅이 함께 사라지기 때문 */
    boolean hasOngoingTrade(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM group_buys g
                                WHERE g.status = 'RECRUITING' AND g.deadline_at > now()
                                  AND (g.host_id = :u OR EXISTS (SELECT 1 FROM group_buy_participants p
                                                                  WHERE p.group_buy_id = g.id AND p.user_id = :u)))
                    OR EXISTS (SELECT 1 FROM market_requests r JOIN market_items i ON i.id = r.item_id
                                WHERE r.status IN ('PENDING', 'ACCEPTED') AND (r.buyer_id = :u OR i.seller_id = :u))
                    OR EXISTS (SELECT 1 FROM group_buy_payments p LEFT JOIN group_buys g ON g.id = p.group_buy_id
                                WHERE p.status IN ('APPROVING', 'CANCELING') AND (p.user_id = :u OR g.host_id = :u))
                    -- 환불은 같이 사기 행으로 대상을 찾으므로, 개설자가 지우면 환불 대기 결제가 환불되지 않음
                    OR EXISTS (SELECT 1 FROM group_buy_payments p JOIN group_buys g ON g.id = p.group_buy_id
                                WHERE p.status = 'PAID' AND g.host_id = :u
                                  AND (g.status = 'CANCELED' OR (g.status = 'RECRUITING'
                                       AND (SELECT COALESCE(sum(q.quantity), 0) FROM group_buy_participants q
                                             WHERE q.group_buy_id = g.id) < g.target_quantity)))
                """, new MapSqlParameterSource("u", userId), Boolean.class));
    }

    /** 사용자 행을 지우면 연관 데이터는 FK의 ON DELETE CASCADE로 지워지고 결제 기록만 연결이 끊긴 채 남음 */
    void deleteUser(long userId) {
        var u = new MapSqlParameterSource("u", userId);
        jdbc.update("DELETE FROM refresh_sessions WHERE principal_id = :u AND role = 'STUDENT'", u);
        jdbc.update("DELETE FROM users WHERE id = :u", u);
    }
}
