package com.moagwa.notification;

import com.moagwa.auth.JwtService;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationRetentionTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRetention retention;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;

    long user() { return users.save(new User("DEV", UUID.randomUUID().toString(), "알림 보관")).getId(); }
    long notification(long user, String title, String read) {
        return jdbc.queryForObject("INSERT INTO notifications(user_id,type,source,notice_id,title,created_at,read_at) "
                + "VALUES (?,'NEW_NOTICE','MANUAL',1,?,now()-INTERVAL '60 days'," + read + ") RETURNING id", Long.class, user, title);
    }
    String token(long user) { return "Bearer " + jwt.issueStudent(user); }

    @Test void expiresAtFourteenDaysAfterReadNotCreationAndKeepsUnreadNotifications() throws Exception {
        long user = user();
        notification(user, "만료", "now()-INTERVAL '14 days'");
        notification(user, "오래된 읽음", "now()-INTERVAL '15 days'");
        notification(user, "보관 중", "now()-INTERVAL '14 days'+INTERVAL '1 second'");
        notification(user, "방금 읽음", "now()");
        notification(user, "오래된 미확인", "NULL");
        mvc.perform(get("/api/me/notifications").header("Authorization", token(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[*].title", containsInAnyOrder("보관 중", "방금 읽음", "오래된 미확인")));
        retention.deleteExpired();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?", Integer.class, user)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL", Integer.class, user)).isEqualTo(1);
        retention.deleteExpired();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?", Integer.class, user)).isEqualTo(3);
    }

    @Test void repeatedSingleAndAllReadDoNotExtendRetentionAndCannotReadOtherUsersRows() throws Exception {
        long owner = user(), other = user();
        long old = notification(owner, "이전 읽음", "now()-INTERVAL '10 days'");
        long unread = notification(owner, "새 알림", "NULL");
        long foreign = notification(other, "다른 사용자", "NULL");
        Instant first = jdbc.queryForObject("SELECT read_at FROM notifications WHERE id=?", java.sql.Timestamp.class, old).toInstant();
        mvc.perform(put("/api/me/notifications/" + old + "/read").header("Authorization", token(owner))).andExpect(status().isNoContent());
        mvc.perform(put("/api/me/notifications/" + foreign + "/read").header("Authorization", token(owner))).andExpect(status().isNoContent());
        mvc.perform(put("/api/me/notifications/read-all").header("Authorization", token(owner))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT read_at FROM notifications WHERE id=?", java.sql.Timestamp.class, old).toInstant()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notifications WHERE id=?", java.sql.Timestamp.class, unread)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT read_at FROM notifications WHERE id=?", java.sql.Timestamp.class, foreign)).isNull();
    }
}
