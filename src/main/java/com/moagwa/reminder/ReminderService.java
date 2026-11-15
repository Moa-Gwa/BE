package com.moagwa.reminder;

import com.moagwa.common.exception.ApiException;
import com.moagwa.notification.PushRequested;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReminderService {
    public enum Kind {
        DEADLINE("reminders"), EVENT_START("event_start_reminders");
        private final String table;
        Kind(String table) { this.table = table; }
    }
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    // 대상을 매번 조인하므로 날짜 변경·삭제·저장 취소가 발송 조건에 즉시 반영된다.
    private static final String TARGETS = """
        SELECT b.user_id, b.source, b.notice_id AS target_id, f.title, f.deadline_at AS at, 'DEADLINE' AS kind, INTERVAL '24 hours' AS lead_time
        FROM bookmarks b JOIN v_feed_items f ON f.source=b.source AND f.id=b.notice_id
        WHERE f.deadline_at IS NOT NULL AND f.display_status IS DISTINCT FROM 'CLOSED'
        UNION ALL
        SELECT b.user_id, b.source, b.notice_id, f.title, f.event_start_at, 'EVENT_START', INTERVAL '1 hour'
        FROM bookmarks b JOIN v_feed_items f ON f.source=b.source AND f.id=b.notice_id
        WHERE f.event_start_at IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM notices n WHERE b.source='MANUAL' AND n.id=b.notice_id AND n.status='CLOSED')
        UNION ALL
        SELECT user_id, 'PERSONAL', id, title, starts_at, 'DEADLINE', INTERVAL '24 hours' FROM personal_schedules
        """;

    public record State(boolean enabled, Instant at, Instant remindAt, boolean tooLate) {}

    public State state(long user, String source, long id) {
        return state(user, source, id, Kind.DEADLINE);
    }

    public State state(long user, String source, long id, Kind kind) {
        var targets = jdbc.query("SELECT at FROM (" + TARGETS + ") t WHERE user_id=? AND source=? AND target_id=? AND kind=?",
                (rs, i) -> rs.getTimestamp(1).toInstant(), user, source, id, kind.name());
        if (targets.isEmpty()) throw ApiException.badRequest("저장한 공지의 해당 날짜 또는 내 일정이 필요합니다.");
        Instant at = targets.get(0);
        boolean enabled = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM " + kind.table + " WHERE user_id=? AND source=? AND target_id=? AND enabled)",
                Boolean.class, user, source, id));
        long seconds = kind == Kind.EVENT_START ? 3600 : 86400;
        return new State(enabled, at, at.minusSeconds(seconds), !at.minusSeconds(seconds).isAfter(Instant.now()));
    }

    @Transactional
    public State set(long user, String source, long id, boolean enabled) {
        return set(user, source, id, enabled, Kind.DEADLINE);
    }

    @Transactional
    public State set(long user, String source, long id, boolean enabled, Kind kind) {
        state(user, source, id, kind);
        jdbc.update("INSERT INTO " + kind.table + " AS r (user_id,source,target_id,enabled) VALUES (?,?,?,?) "
            + "ON CONFLICT(user_id,source,target_id) DO UPDATE SET enabled=EXCLUDED.enabled, "
            + "enabled_at=CASE WHEN NOT r.enabled AND EXCLUDED.enabled THEN now() ELSE r.enabled_at END",
            user, source, id, enabled);
        return state(user, source, id, kind);
    }

    public void remove(long user, String source, long id) {
        for (Kind kind : Kind.values()) {
            jdbc.update("DELETE FROM " + kind.table + " WHERE user_id=? AND source=? AND target_id=?", user, source, id);
        }
    }

    @Transactional
    public int sendDue() {
        int sent = 0;
        for (Kind kind : Kind.values()) sent += sendDue(kind);
        return sent;
    }

    private int sendDue(Kind kind) {
        var due = jdbc.query("SELECT r.user_id,r.source,r.target_id,t.title,t.at,t.kind FROM " + kind.table + " r JOIN ("
            + TARGETS + """
            ) t ON t.user_id=r.user_id AND t.source=r.source AND t.target_id=r.target_id AND t.kind=?
            LEFT JOIN notification_settings ns ON ns.user_id=r.user_id
            WHERE r.enabled AND CASE WHEN t.kind='EVENT_START' THEN COALESCE(ns.event_start,TRUE) ELSE COALESCE(ns.deadline_soon,TRUE) END
              AND t.at > now() AND t.at - t.lead_time <= now()
              AND t.at - t.lead_time >= now() - INTERVAL '1 hour'
              AND t.at - t.lead_time > r.enabled_at
              AND r.sent_for IS DISTINCT FROM t.at
            ORDER BY t.at LIMIT 100 FOR UPDATE OF r SKIP LOCKED
            """, (rs,i) -> new Due(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),
                        rs.getTimestamp(5).toInstant(),Kind.valueOf(rs.getString(6))), kind.name());
        for (var d : due) {
            String body = d.kind() == Kind.EVENT_START ? "저장한 공지의 행사 시작까지 약 1시간 남았어요." : d.source().equals("PERSONAL") ? "내 일정 시작까지 약 24시간 남았어요." : "저장한 공지 마감까지 약 24시간 남았어요.";
            jdbc.update("INSERT INTO notifications(user_id,type,source,notice_id,title,body) VALUES (?,'REMINDER',?,?,?,?)",
                    d.user(),d.source(),d.id(),d.title().substring(0,Math.min(200,d.title().length())),body);
            jdbc.update("UPDATE " + kind.table + " SET sent_for=? WHERE user_id=? AND source=? AND target_id=?",
                    java.sql.Timestamp.from(d.at()),d.user(),d.source(),d.id());
            String url = d.source().equals("PERSONAL") ? "/schedule" : "/notices/"+d.source().toLowerCase()+"/"+d.id();
            events.publishEvent(new PushRequested(List.of(d.user()),d.title(),body,url));
        }
        return due.size();
    }
    private record Due(long user,String source,long id,String title,Instant at,Kind kind) {}
}
