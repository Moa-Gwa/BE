package com.moagwa.notification;

import com.moagwa.crawler.CrawledNoticeCreatedEvent;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
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
        return jdbc.update("""
                INSERT INTO notifications (user_id, type, source, notice_id, title, body)
                SELECT u.id, :type, :source, :nid, :title, o.name
                  FROM users u
                  JOIN organizations o ON o.id = :org
                  LEFT JOIN notification_settings ns ON ns.user_id = u.id
                 WHERE """ + MATCH.formatted(categoryMatch) + " AND " + settingCheck, p);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCrawled(CrawledNoticeCreatedEvent e) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("org", e.organizationId()).addValue("nid", e.noticeId()).addValue("title", e.title())
                .addValue("type", "NEW_NOTICE").addValue("source", "CRAWLED");
        jdbc.update("""
                INSERT INTO notifications (user_id, type, source, notice_id, title, body)
                SELECT u.id, :type, :source, :nid, :title, o.name
                  FROM users u
                  JOIN organizations o ON o.id = :org
                  LEFT JOIN notification_settings ns ON ns.user_id = u.id
                 WHERE """ + MATCH.formatted("") + " AND COALESCE(ns.new_notice, TRUE)", p);
    }
}
