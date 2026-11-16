package com.moagwa.crawler;

import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.crawler.fetch.PageFetcher;
import com.moagwa.notification.PushRequested;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@RecordApplicationEvents
@Import(CrawlerNotificationTest.ProbeConfig.class)
class CrawlerNotificationTest {
    @Autowired CrawlerService crawler;
    @Autowired CrawlSourceRepository sources;
    @Autowired CrawledNoticeRepository notices;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEvents events;
    @Autowired CommittedPushes pushes;
    @Autowired InstagramPersistence instagram;
    @MockBean PageFetcher fetcher;
    CrawlSource source;
    long primary, interested, muted, unrelated;

    @BeforeEach void setup() {
        pushes.items.clear();
        String tag = UUID.randomUUID().toString();
        var org = organizations.saveAndFlush(new Organization("알림 회귀 " + tag, OrganizationType.DEPARTMENT));
        source = sources.saveAndFlush(new CrawlSource("notification-" + tag, org, "https://school.example/list.do"));
        primary = student(org.getId());
        interested = student(null);
        muted = student(org.getId());
        unrelated = student(null);
        jdbc.update("INSERT INTO user_interests(user_id,kind,target_id) VALUES (?,'DEPARTMENT',?)", interested, org.getId());
        jdbc.update("INSERT INTO notification_settings(user_id,new_notice) VALUES (?,false)", muted);
    }

    long student(Long department) {
        var user = new User("DEV", UUID.randomUUID().toString(), "알림 테스트");
        user.updateProfile(null, department, null);
        return users.saveAndFlush(user).getId();
    }
    void activeSource() {
        source.markSuccess(Instant.now(), false);
        source = sources.saveAndFlush(source);
    }
    void site(String id, String title) throws IOException {
        doReturn(Jsoup.parse("<div class='b-con-box'>본문</div>")).when(fetcher).fetch(anyString());
        doReturn(Jsoup.parse("<table><tbody><tr><td><a class='b-title' href='?articleNo=" + id
                + "'>" + title + "</a></td><td class='b-date'>2026.11.16</td></tr></tbody></table>", source.getListUrl()))
                .when(fetcher).fetch(source.getListUrl());
    }
    List<Long> recipients(long notice) {
        return jdbc.queryForList("SELECT user_id FROM notifications WHERE source='CRAWLED' AND notice_id=? ORDER BY user_id", Long.class, notice);
    }
    long noticeId(String external) {
        return jdbc.queryForObject("SELECT id FROM crawled_notices WHERE source_id=? AND external_id=?", Long.class, source.getId(), external);
    }

    @Test void newSchoolNoticeCreatesNotificationsAndRequestsPushAfterCommitOnlyForEligibleUsers() throws Exception {
        activeSource();
        site("1", "새 학교 공지");
        assertThat(crawler.crawlSource(source).newNotices()).isEqualTo(1);
        long id = noticeId("1");
        assertThat(recipients(id)).containsExactlyInAnyOrder(primary, interested).doesNotContain(muted, unrelated);
        assertThat(pushes.items).hasSize(1).first().satisfies(push -> {
            assertThat(push.userIds()).containsExactlyInAnyOrder(primary, interested);
            assertThat(push.url()).isEqualTo("/notices/crawled/" + id);
        });
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).hasSize(1);
    }

    @Test void firstAndRepeatedCrawlsDoNotNotifyAndOnlyTheNextNewNoticeDoes() throws Exception {
        site("1", "과거 공지");
        assertThat(crawler.crawlSource(source).newNotices()).isEqualTo(1);
        assertThat(recipients(noticeId("1"))).isEmpty();
        assertThat(pushes.items).isEmpty();
        source = sources.findById(source.getId()).orElseThrow();
        assertThat(crawler.crawlSource(source).newNotices()).isZero();
        site("2", "새 공지");
        assertThat(crawler.crawlSource(source).newNotices()).isEqualTo(1);
        assertThat(recipients(noticeId("2"))).containsExactlyInAnyOrder(primary, interested);
        assertThat(crawler.crawlSource(source).newNotices()).isZero();
        assertThat(pushes.items).hasSize(1);
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).hasSize(1);
    }

    @Test void rolledBackSchoolSaveProducesNeitherNotificationsNorCommittedPushes() throws Exception {
        activeSource();
        site("1", "롤백 공지");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(crawler.crawlSource(source).newNotices()).isEqualTo(1);
            assertThat(pushes.items).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crawled_notices WHERE source_id=?", Long.class, source.getId())).isZero();
        assertThat(pushes.items).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id IN (?,?)", Long.class, primary, interested)).isZero();
    }

    @Test void failedSchoolInsertDoesNotPublishNotificationEvents() throws Exception {
        activeSource();
        site("1", "제".repeat(501));
        assertThat(crawler.crawlSource(source).newNotices()).isZero();
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).isEmpty();
        assertThat(pushes.items).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id IN (?,?)", Long.class, primary, interested)).isZero();
    }

    @Test void instagramStillSuppressesInitialAndDuplicateImportsAndNotifiesNewPostsAfterCommit() {
        String tag = UUID.randomUUID().toString();
        var account = new InstagramAccountCatalog.InstagramAccount("인스타 알림 " + tag, "test_" + tag, "https://www.instagram.com/test/");
        var first = post(account.username(), "old");
        assertThat(instagram.importPosts(account, List.of(first), true)).isEqualTo(1);
        var source = sources.findByCode("instagram_student_council_" + account.username()).orElseThrow();
        jdbc.update("INSERT INTO user_interests(user_id,kind,target_id) VALUES (?,'ORGANIZATION',?)", interested, source.getOrganization().getId());
        assertThat(pushes.items).isEmpty();
        assertThat(instagram.importPosts(account, List.of(first, post(account.username(), "new")), true)).isEqualTo(1);
        assertThat(pushes.items).hasSize(1).first().satisfies(push -> assertThat(push.userIds()).containsExactly(interested));
        long id = jdbc.queryForObject("SELECT id FROM crawled_notices WHERE source_id=? AND external_id='new'", Long.class, source.getId());
        assertThat(recipients(id)).containsExactly(interested);
        assertThat(instagram.importPosts(account, List.of(first, post(account.username(), "new")), true)).isZero();
        assertThat(pushes.items).hasSize(1);
    }
    private InstagramPostDetailsCollector.InstagramPost post(String user, String id) {
        return new InstagramPostDetailsCollector.InstagramPost(user, id, "인스타 " + id, "https://www.instagram.com/p/" + id, Instant.now(), "본문", List.of());
    }

    @TestConfiguration
    static class ProbeConfig { @Bean CommittedPushes committedPushes() { return new CommittedPushes(); } }
    static class CommittedPushes {
        final List<PushRequested> items = new CopyOnWriteArrayList<>();
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(PushRequested push) { items.add(push); }
    }
}
