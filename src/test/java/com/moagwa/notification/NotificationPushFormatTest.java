package com.moagwa.notification;

import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@RecordApplicationEvents
class NotificationPushFormatTest {
    @Autowired NotificationService notifications;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noticePushShowsSenderThenOriginalTitleAndKeepsInAppNotification(boolean urgent) {
        var org = organizations.saveAndFlush(new Organization("푸시 검사 학과 " + UUID.randomUUID(), OrganizationType.DEPARTMENT));
        var user = new User("DEV", UUID.randomUUID().toString(), "푸시 검사");
        user.updateProfile(null, org.getId(), null);
        long userId = users.saveAndFlush(user).getId();
        String title = "[장학] 2026학년도 장학금 신청 안내";

        assertThat(notifications.notifyManual(123, org.getId(), List.of(), title, urgent)).isEqualTo(1);

        assertThat(events.stream(PushRequested.class)).singleElement().satisfies(push -> {
            assertThat(push.userIds()).containsExactly(userId);
            assertThat(push.title()).isEqualTo("[" + org.getName() + "] " + (urgent ? "긴급 공지" : "새 공지"));
            assertThat(push.body()).isEqualTo(title);
            assertThat(push.url()).isEqualTo("/notices/manual/123");
        });
        var stored = jdbc.queryForMap("SELECT title,body,type FROM notifications WHERE user_id=?", userId);
        assertThat(stored).containsEntry("title", title).containsEntry("body", org.getName())
                .containsEntry("type", urgent ? "URGENT" : "NEW_NOTICE");
    }

    @Test
    void noRecipientsMeansNoPush() {
        var org = organizations.saveAndFlush(new Organization("알림 없는 학과", OrganizationType.DEPARTMENT));
        assertThat(notifications.notifyManual(123, org.getId(), List.of(), "새 공지", false)).isZero();
        assertThat(events.stream(PushRequested.class)).isEmpty();
    }

    @Test
    void tradePushKeepsItsOriginalTitleBodyAndLink() {
        long user = users.saveAndFlush(new User("DEV", UUID.randomUUID().toString(), "거래 검사")).getId();
        notifications.notifyTrade(user, "TRADE_REQUESTED", 456, "새 거래 요청", "교재 구매 희망");
        assertThat(events.stream(PushRequested.class)).singleElement().satisfies(push -> {
            assertThat(push.userIds()).containsExactly(user);
            assertThat(push.title()).isEqualTo("새 거래 요청");
            assertThat(push.body()).isEqualTo("교재 구매 희망");
            assertThat(push.url()).isEqualTo("/life/items/456");
        });
    }
}
