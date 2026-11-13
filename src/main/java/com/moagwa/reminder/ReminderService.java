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
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    // 대상을 매번 조인하므로 날짜 변경·삭제·저장 취소가 발송 조건에 즉시 반영된다.
    private static final String TARGETS = """
        SELECT b.user_id, b.source, b.notice_id AS target_id, f.title, f.deadline_at AS at
        FROM bookmarks b JOIN v_feed_items f ON f.source=b.source AND f.id=b.notice_id
        WHERE f.deadline_at IS NOT NULL AND f.display_status IS DISTINCT FROM 'CLOSED'
        UNION ALL
        SELECT user_id, 'PERSONAL', id, title, starts_at FROM personal_schedules
        """;

    public record State(boolean enabled, Instant at, Instant remindAt, boolean tooLate) {}

    public State state(long user, String source, long id) {
        var targets = jdbc.query("SELECT at FROM (" + TARGETS + ") t WHERE user_id=? AND source=? AND target_id=?",
                (rs, i) -> rs.getTimestamp(1).toInstant(), user, source, id);
        if (targets.isEmpty()) throw ApiException.badRequest("저장한 공지의 마감일 또는 내 일정이 필요합니다.");
        Instant at = targets.get(0);
        boolean enabled = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM reminders WHERE user_id=? AND source=? AND target_id=? AND enabled)",
                Boolean.class, user, source, id));
        return new State(enabled, at, at.minusSeconds(86400), !at.minusSeconds(86400).isAfter(Instant.now()));
    }

    @Transactional
    public State set(long user, String source, long id, boolean enabled) {
        state(user, source, id);
        jdbc.update("""
            INSERT INTO reminders(user_id,source,target_id,enabled) VALUES (?,?,?,?)
            ON CONFLICT(user_id,source,target_id) DO UPDATE SET enabled=EXCLUDED.enabled,
              enabled_at=CASE WHEN NOT reminders.enabled AND EXCLUDED.enabled THEN now() ELSE reminders.enabled_at END
            """, user, source, id, enabled);
        return state(user, source, id);
    }

    public void remove(long user, String source, long id) {
        jdbc.update("DELETE FROM reminders WHERE user_id=? AND source=? AND target_id=?", user, source, id);
    }

    @Transactional
    public int sendDue() {
        var due = jdbc.query("""
            SELECT r.user_id,r.source,r.target_id,t.title,t.at
            FROM reminders r JOIN (
            """ + TARGETS + """
            ) t ON t.user_id=r.user_id AND t.source=r.source AND t.target_id=r.target_id
            LEFT JOIN notification_settings ns ON ns.user_id=r.user_id
            WHERE r.enabled AND COALESCE(ns.deadline_soon,TRUE)
              AND t.at > now() AND t.at - INTERVAL '24 hours' <= now()
              AND t.at - INTERVAL '24 hours' >= now() - INTERVAL '1 hour'
              AND t.at - INTERVAL '24 hours' > r.enabled_at
              AND r.sent_for IS DISTINCT FROM t.at
            ORDER BY t.at LIMIT 100 FOR UPDATE OF r SKIP LOCKED
            """, (rs,i) -> new Due(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),
                        rs.getTimestamp(5).toInstant()));
        for (var d : due) {
            String body = d.source().equals("PERSONAL") ? "내 일정 시작까지 약 24시간 남았어요." : "저장한 공지 마감까지 약 24시간 남았어요.";
            jdbc.update("INSERT INTO notifications(user_id,type,source,notice_id,title,body) VALUES (?,'REMINDER',?,?,?,?)",
                    d.user(),d.source(),d.id(),d.title().substring(0,Math.min(200,d.title().length())),body);
            jdbc.update("UPDATE reminders SET sent_for=? WHERE user_id=? AND source=? AND target_id=?",
                    java.sql.Timestamp.from(d.at()),d.user(),d.source(),d.id());
            String url = d.source().equals("PERSONAL") ? "/schedule" : "/notices/"+d.source().toLowerCase()+"/"+d.id();
            events.publishEvent(new PushRequested(List.of(d.user()),d.title(),body,url));
        }
        return due.size();
    }
    private record Due(long user,String source,long id,String title,Instant at) {}
}
