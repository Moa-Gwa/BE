package com.moagwa.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Only read notifications expire; repeat reads do not reset their original read_at. */
@Component
@RequiredArgsConstructor
public class NotificationRetention {
    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public int deleteExpired() {
        return jdbc.update("DELETE FROM notifications WHERE read_at <= now() - INTERVAL '14 days'");
    }
}
