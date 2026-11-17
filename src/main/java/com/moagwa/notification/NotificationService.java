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
 * Creates in-app notifications for students whose interests match a notice, and for the two sides of a
 * second-hand trade. Push delivery (FCM) consumes the same rows.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String MATCH = """
            (o.is_required
             OR u.primary_department_id = :org
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

    /**
     * 거래 당사자 한 명에게 알림을 만들고 커밋 후 푸시함. 호출자 트랜잭션 안에서 실행됨
     * source는 MARKET, notice_id 자리에는 물품 id를 저장해 알림을 누르면 물품 화면으로 이동함
     */
    @Transactional
    public void notifyTrade(long userId, String type, long itemId, String title, String body) {
        jdbc.update("""
                INSERT INTO notifications (user_id, type, source, notice_id, title, body)
                VALUES (:user, :type, 'MARKET', :item, :title, :body)""",
                new MapSqlParameterSource().addValue("user", userId).addValue("type", type)
                        .addValue("item", itemId).addValue("title", title).addValue("body", body));
        events.publishEvent(new PushRequested(List.of(userId), title, body, "/life/items/" + itemId));
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
            String pushTitle = "[" + rows.get(0).body() + "] "
                    + ("URGENT".equals(params.getValue("type")) ? "긴급 공지" : "새 공지");
            events.publishEvent(new PushRequested(rows.stream().map(Recipient::userId).toList(), pushTitle,
                    title, "/notices/" + source.toLowerCase() + "/" + noticeId));
        }
        return rows.size();
    }

    private record Recipient(long userId, String body) {}
}
