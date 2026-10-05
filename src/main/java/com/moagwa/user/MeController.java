package com.moagwa.user;

import com.moagwa.auth.CurrentUser;
import com.moagwa.common.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Everything a signed-in student manages about themself. The user id always comes from the token. */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
class MeController {

    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;

    @GetMapping("/profile")
    @Transactional(readOnly = true)
    Profile profile(Authentication auth) {
        User u = current(auth);
        return new Profile(u.getId(), u.getNickname(), u.getPrimaryDepartmentId(), u.getAdmissionYear(),
                interests(u.getId(), "DEPARTMENT"), interests(u.getId(), "ORGANIZATION"),
                interests(u.getId(), "CATEGORY"));
    }

    @PutMapping("/profile")
    @Transactional
    Profile updateProfile(@Valid @RequestBody ProfileUpdate req, Authentication auth) {
        User u = current(auth);
        List<Long> departments = orEmpty(req.departmentIds());
        List<Long> organizations = orEmpty(req.organizationIds());
        List<Long> categories = orEmpty(req.categoryIds());
        if (req.primaryDepartmentId() != null) {
            requireOrganizations(List.of(req.primaryDepartmentId()), "DEPARTMENT", "주전공");
        }
        requireOrganizations(departments, "DEPARTMENT", "관심 학과");
        requireOrganizations(organizations, "STUDENT_GOVERNMENT", "관심 학생자치단체");
        Integer found = jdbc.queryForObject("SELECT count(*) FROM categories WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", categories.isEmpty() ? List.of(-1L) : categories), Integer.class);
        if (found == null || found != categories.size()) {
            throw ApiException.badRequest("존재하지 않는 카테고리가 포함되어 있습니다.");
        }
        u.updateProfile(req.nickname(), req.primaryDepartmentId(), req.admissionYear());
        jdbc.update("DELETE FROM user_interests WHERE user_id = :u", new MapSqlParameterSource("u", u.getId()));
        insertInterests(u.getId(), "DEPARTMENT", departments);
        insertInterests(u.getId(), "ORGANIZATION", organizations);
        insertInterests(u.getId(), "CATEGORY", categories);
        return profile(auth);
    }

    @PutMapping("/bookmarks/{source}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void bookmark(@PathVariable String source, @PathVariable long id, Authentication auth) {
        String s = source(source);
        long me = CurrentUser.from(auth).id();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("u", me).addValue("s", s).addValue("n", id);
        int inserted = jdbc.update("""
                INSERT INTO bookmarks (user_id, source, notice_id) VALUES (:u, :s, :n)
                ON CONFLICT DO NOTHING""", p);
        if (inserted > 0 && s.equals("MANUAL")) {
            jdbc.update("UPDATE notices SET bookmark_count = bookmark_count + 1 WHERE id = :n", p);
        }
    }

    @DeleteMapping("/bookmarks/{source}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void unbookmark(@PathVariable String source, @PathVariable long id, Authentication auth) {
        String s = source(source);
        long me = CurrentUser.from(auth).id();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("u", me).addValue("s", s).addValue("n", id);
        int deleted = jdbc.update("DELETE FROM bookmarks WHERE user_id = :u AND source = :s AND notice_id = :n", p);
        if (deleted > 0 && s.equals("MANUAL")) {
            jdbc.update("UPDATE notices SET bookmark_count = GREATEST(bookmark_count - 1, 0) WHERE id = :n", p);
        }
    }

    @GetMapping("/notifications")
    NotificationList notifications(Authentication auth) {
        long me = CurrentUser.from(auth).id();
        MapSqlParameterSource p = new MapSqlParameterSource("u", me);
        List<NotificationView> items = jdbc.query("""
                SELECT id, type, source, notice_id, title, body, read_at, created_at FROM notifications
                 WHERE user_id = :u ORDER BY created_at DESC, id DESC LIMIT 50""", p, (rs, i) -> {
            Timestamp read = rs.getTimestamp("read_at");
            return new NotificationView(rs.getLong("id"), rs.getString("type"), rs.getString("source"),
                    rs.getLong("notice_id"), rs.getString("title"), rs.getString("body"), read != null,
                    rs.getTimestamp("created_at").toInstant().toString());
        });
        Integer unread = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = :u AND read_at IS NULL", p, Integer.class);
        return new NotificationList(unread == null ? 0 : unread, items);
    }

    @PutMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@PathVariable long id, Authentication auth) {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE id = :id AND user_id = :u AND read_at IS NULL",
                new MapSqlParameterSource().addValue("id", id).addValue("u", CurrentUser.from(auth).id()));
    }

    @PutMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markAllRead(Authentication auth) {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE user_id = :u AND read_at IS NULL",
                new MapSqlParameterSource("u", CurrentUser.from(auth).id()));
    }

    @GetMapping("/notification-settings")
    NotificationSettings settings(Authentication auth) {
        List<NotificationSettings> rows = jdbc.query("SELECT * FROM notification_settings WHERE user_id = :u",
                new MapSqlParameterSource("u", CurrentUser.from(auth).id()),
                (rs, i) -> new NotificationSettings(rs.getBoolean("new_notice"), rs.getBoolean("event_start"),
                        rs.getBoolean("deadline_soon"), rs.getBoolean("urgent")));
        return rows.isEmpty() ? new NotificationSettings(true, true, true, true) : rows.get(0);
    }

    @PutMapping("/notification-settings")
    @Transactional
    NotificationSettings updateSettings(@RequestBody NotificationSettings s, Authentication auth) {
        jdbc.update("""
                INSERT INTO notification_settings (user_id, new_notice, event_start, deadline_soon, urgent)
                VALUES (:u, :a, :b, :c, :d)
                ON CONFLICT (user_id) DO UPDATE SET new_notice = :a, event_start = :b, deadline_soon = :c, urgent = :d""",
                new MapSqlParameterSource().addValue("u", CurrentUser.from(auth).id())
                        .addValue("a", s.newNotice()).addValue("b", s.eventStart())
                        .addValue("c", s.deadlineSoon()).addValue("d", s.urgent()));
        return s;
    }

    private User current(Authentication auth) {
        return users.findById(CurrentUser.from(auth).id())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
    }

    private List<Long> interests(long userId, String kind) {
        return jdbc.queryForList("SELECT target_id FROM user_interests WHERE user_id = :u AND kind = :k ORDER BY target_id",
                new MapSqlParameterSource().addValue("u", userId).addValue("k", kind), Long.class);
    }

    private void insertInterests(long userId, String kind, List<Long> ids) {
        for (Long id : ids.stream().distinct().toList()) {
            jdbc.update("INSERT INTO user_interests (user_id, kind, target_id) VALUES (:u, :k, :t)",
                    new MapSqlParameterSource().addValue("u", userId).addValue("k", kind).addValue("t", id));
        }
    }

    private void requireOrganizations(List<Long> ids, String type, String label) {
        if (ids.isEmpty()) {
            return;
        }
        Integer found = jdbc.queryForObject("SELECT count(*) FROM organizations WHERE id IN (:ids) AND type = :t",
                new MapSqlParameterSource().addValue("ids", ids.stream().distinct().toList()).addValue("t", type),
                Integer.class);
        if (found == null || found != ids.stream().distinct().count()) {
            throw ApiException.badRequest(label + " 선택이 올바르지 않습니다.");
        }
    }

    private static String source(String s) {
        String upper = s.toUpperCase();
        if (!upper.equals("MANUAL") && !upper.equals("CRAWLED")) {
            throw ApiException.badRequest("알 수 없는 공지 출처입니다.");
        }
        return upper;
    }

    private static List<Long> orEmpty(List<Long> l) {
        return l == null ? new ArrayList<>() : l;
    }

    record Profile(long id, String nickname, Long primaryDepartmentId, Integer admissionYear,
                   List<Long> departmentIds, List<Long> organizationIds, List<Long> categoryIds) {
    }

    record ProfileUpdate(@Size(max = 50) String nickname, Long primaryDepartmentId, Integer admissionYear,
                         List<Long> departmentIds, List<Long> organizationIds, List<Long> categoryIds) {
    }

    record NotificationView(long id, String type, String source, long noticeId, String title, String body,
                            boolean read, String createdAt) {
    }

    record NotificationList(int unreadCount, List<NotificationView> items) {
    }

    record NotificationSettings(boolean newNotice, boolean eventStart, boolean deadlineSoon, boolean urgent) {
    }
}
