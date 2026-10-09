package com.moagwa.notification;

import com.moagwa.crawler.CrawledNoticeCreatedEvent;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Creates in-app notifications for students whose interests match a notice. Push delivery (FCM) is a later
 * step and will consume the same rows.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String MATCH = """
            (u.primary_department_id = :org
             OR EXISTS (SELECT 1 FROM user_interests ui WHERE ui.user_id = u.id
                          AND ui.kind IN ('DEPARTMENT', 'ORGANIZATION') AND ui.target_id = :org)
             %s)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    /** Must run inside the caller's transaction so notifications commit together with the notice. */
    @Transactional
    public int notifyManual(long noticeId, long organizationId, Collection<Long> categoryIds, String title,
                            boolean urgent) {
        String type = urgent ? "URGENT" : "NEW_NOTICE";
        String settingCheck = urgent ? "COALESCE(ns.urgent, TRUE)" : "COALESCE(ns.new_notice, TRUE)";
        String categoryMatch = categoryIds.isEmpty() ? ""
                : "OR EXISTS (SELECT 1 FROM user_interests uc WHERE uc.user_id = u.id AND uc.kind = 'CATEGORY' "
                + "AND uc.target_id IN (:cats))";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("org", organizationId).addValue("nid", noticeId).addValue("title", title)
                .addValue("type", type).addValue("source", "MANUAL");
        if (!categoryIds.isEmpty()) {
            p.addValue("cats", categoryIds);
        }
        return insertAndPush("""
                INSERT INTO notifications (user_id, type, source, notice_id, title, body)
                SELECT u.id, :type, :source, :nid, :title, o.name
                  FROM users u
                  JOIN organizations o ON o.id = :org
                  LEFT JOIN notification_settings ns ON ns.user_id = u.id
                 WHERE """ + MATCH.formatted(categoryMatch) + " AND " + settingCheck, p, "MANUAL", noticeId, title);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCrawled(CrawledNoticeCreatedEvent e) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("org", e.organizationId()).addValue("nid", e.noticeId()).addValue("title", e.title())
                .addValue("type", "NEW_NOTICE").addValue("source", "CRAWLED");
        insertAndPush("""
                INSERT INTO notifications (user_id, type, source, notice_id, title, body)
                SELECT u.id, :type, :source, :nid, :title, o.name
                  FROM users u
                  JOIN organizations o ON o.id = :org
                  LEFT JOIN notification_settings ns ON ns.user_id = u.id
                 WHERE """ + MATCH.formatted("") + " AND COALESCE(ns.new_notice, TRUE)", p, "CRAWLED",
                e.noticeId(), e.title());
    }

    /** Inserts the notification rows and asks {@link PushSender} to push to the same recipients after the commit. */
    private int insertAndPush(String insertSql, MapSqlParameterSource params, String source, long noticeId,
                              String title) {
        List<Recipient> rows = jdbc.query(insertSql + " RETURNING user_id, body", params,
                (rs, i) -> new Recipient(rs.getLong("user_id"), rs.getString("body")));
        if (!rows.isEmpty()) {
            events.publishEvent(new PushRequested(rows.stream().map(Recipient::userId).toList(), source, noticeId,
                    title, rows.get(0).body()));
        }
        return rows.size();
    }

    private record Recipient(long userId, String body) {}
}
