package com.moagwa.reminder;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.moagwa.auth.JwtService;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="moagwa.reminder.interval=PT24H")
@AutoConfigureMockMvc
class ReminderTest {
    @Autowired ReminderService reminders;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    long user, id;
    @BeforeEach void setup() {
        user = users.save(new User("DEV",UUID.randomUUID().toString(),"reminder")).getId();
        id = jdbc.queryForObject("INSERT INTO personal_schedules(user_id,title,starts_at) VALUES (?, '회의',now()+INTERVAL '23 hours 50 minutes') RETURNING id",Long.class,user);
    }
    void enableDue(String source,long target) {
        reminders.set(user,source,target,true);
        jdbc.update("UPDATE reminders SET enabled_at=now()-INTERVAL '2 days' WHERE user_id=?",user);
    }
    int count() { return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND type='REMINDER'",Integer.class,user); }

    @Test void sendsOnceAndTracksDateChanges() {
        enableDue("PERSONAL",id);
        reminders.sendDue(); reminders.sendDue();
        assertThat(count()).isEqualTo(1);
        jdbc.update("UPDATE personal_schedules SET starts_at=now()+INTERVAL '2 days' WHERE id=?",id);
        reminders.sendDue(); assertThat(count()).isEqualTo(1);
        jdbc.update("UPDATE personal_schedules SET starts_at=now()+INTERVAL '23 hours 55 minutes' WHERE id=?",id);
        reminders.sendDue(); assertThat(count()).isEqualTo(2);
    }
    @Test void periodEndEditsDoNotChangeTheStartReminderOrSendDuplicates() {
        jdbc.update("UPDATE personal_schedules SET ends_at=starts_at+INTERVAL '3 days' WHERE id=?", id);
        enableDue("PERSONAL", id);
        var before = reminders.state(user, "PERSONAL", id);
        assertThat(before.remindAt()).isEqualTo(before.at().minusSeconds(86400));
        reminders.sendDue();
        assertThat(count()).isEqualTo(1);
        jdbc.update("UPDATE personal_schedules SET ends_at=ends_at+INTERVAL '2 days' WHERE id=?", id);
        assertThat(reminders.state(user, "PERSONAL", id).remindAt()).isEqualTo(before.remindAt());
        reminders.sendDue();
        assertThat(count()).isEqualTo(1);
        jdbc.update("UPDATE personal_schedules SET starts_at=now()+INTERVAL '23 hours 55 minutes' WHERE id=?", id);
        reminders.sendDue();
        assertThat(count()).isEqualTo(2);
    }

    @Test void lateOptInOffDeletedExpiredAndGlobalOffDoNotSend() {
        reminders.set(user,"PERSONAL",id,true);
        reminders.sendDue(); assertThat(count()).isZero();
        enableDue("PERSONAL",id); reminders.set(user,"PERSONAL",id,false);
        reminders.sendDue(); assertThat(count()).isZero();
        enableDue("PERSONAL",id);
        jdbc.update("INSERT INTO notification_settings(user_id,deadline_soon) VALUES (?,false)",user);
        reminders.sendDue(); assertThat(count()).isZero();
        jdbc.update("UPDATE notification_settings SET deadline_soon=true WHERE user_id=?",user);
        jdbc.update("UPDATE personal_schedules SET starts_at=now()-INTERVAL '1 minute' WHERE id=?",id);
        reminders.sendDue(); assertThat(count()).isZero();
        jdbc.update("DELETE FROM personal_schedules WHERE id=?",id);
        reminders.sendDue(); assertThat(count()).isZero();
    }
    @Test void crawledBookmarksAndUnsaveAreRespected() {
        long source=jdbc.queryForObject("SELECT min(id) FROM crawl_sources",Long.class);
        long notice=jdbc.queryForObject("INSERT INTO crawled_notices(source_id,external_id,title,url,published_at,deadline_at) VALUES (?,?, '학교 공지','https://example.test',now(),now()+INTERVAL '23 hours 50 minutes') RETURNING id",Long.class,source,UUID.randomUUID().toString());
        assertThatThrownBy(()->reminders.set(user,"CRAWLED",notice,true)).isInstanceOf(RuntimeException.class);
        jdbc.update("INSERT INTO bookmarks(user_id,source,notice_id) VALUES (?,'CRAWLED',?)",user,notice);
        enableDue("CRAWLED",notice);
        reminders.sendDue(); assertThat(count()).isEqualTo(1);
        jdbc.update("DELETE FROM bookmarks WHERE user_id=?",user);
        jdbc.update("UPDATE reminders SET sent_for=NULL WHERE user_id=?",user);
        reminders.sendDue(); assertThat(count()).isEqualTo(1);
    }
    @Test void manualNoticeUnbookmarkCancelsReminder() throws Exception {
        long org=jdbc.queryForObject("SELECT min(id) FROM organizations",Long.class);
        long admin=jdbc.queryForObject("INSERT INTO admins(login_id,password_hash,name,organization_id) VALUES (?,'unused','담당자',?) RETURNING id",Long.class,UUID.randomUUID().toString(),org);
        long notice=jdbc.queryForObject("INSERT INTO notices(organization_id,author_id,title,description,status,publish_at,deadline_at) VALUES (?,?,'모집','내용','PUBLISHED',now(),now()+INTERVAL '23 hours 50 minutes') RETURNING id",Long.class,org,admin);
        jdbc.update("INSERT INTO bookmarks(user_id,source,notice_id) VALUES (?,'MANUAL',?)",user,notice);
        enableDue("MANUAL",notice);
        mvc.perform(delete("/api/me/bookmarks/MANUAL/"+notice).header("Authorization","Bearer "+jwt.issueStudent(user)))
          .andExpect(status().isNoContent());
        reminders.sendDue(); assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reminders WHERE user_id=?",Integer.class,user)).isZero();
    }
    @Test void closedManualNoticesDoNotSend() {
        long org=jdbc.queryForObject("SELECT min(id) FROM organizations",Long.class);
        long admin=jdbc.queryForObject("INSERT INTO admins(login_id,password_hash,name,organization_id) VALUES (?,'unused','담당자',?) RETURNING id",Long.class,UUID.randomUUID().toString(),org);
        long notice=jdbc.queryForObject("INSERT INTO notices(organization_id,author_id,title,description,status,publish_at,deadline_at) VALUES (?,?,'모집','내용','PUBLISHED',now(),now()+INTERVAL '23 hours 50 minutes') RETURNING id",Long.class,org,admin);
        jdbc.update("INSERT INTO bookmarks(user_id,source,notice_id) VALUES (?,'MANUAL',?)",user,notice);
        enableDue("MANUAL",notice);
        jdbc.update("UPDATE notices SET status='CLOSED' WHERE id=?",notice);
        reminders.sendDue(); assertThat(count()).isZero();
    }

    @Test void eventStartAndDeadlineRespectIndependentSettingsAndDeduplication() throws Exception {
        long source=jdbc.queryForObject("SELECT min(id) FROM crawl_sources",Long.class);
        long notice=jdbc.queryForObject("INSERT INTO crawled_notices(source_id,external_id,title,url,published_at,event_start_at,deadline_at) VALUES (?,?, '행사','https://example.test',now(),now()+INTERVAL '50 minutes',now()+INTERVAL '23 hours 50 minutes') RETURNING id",Long.class,source,UUID.randomUUID().toString());
        jdbc.update("INSERT INTO bookmarks(user_id,source,notice_id) VALUES (?,'CRAWLED',?)",user,notice);
        reminders.set(user,"CRAWLED",notice,true,ReminderService.Kind.EVENT_START);
        reminders.set(user,"CRAWLED",notice,true);
        jdbc.update("UPDATE reminders SET enabled_at=now()-INTERVAL '2 days' WHERE user_id=?",user);
        jdbc.update("UPDATE event_start_reminders SET enabled_at=now()-INTERVAL '2 days' WHERE user_id=?",user);
        jdbc.update("INSERT INTO notification_settings(user_id,event_start,deadline_soon) VALUES (?,false,true)",user);
        reminders.sendDue(); reminders.sendDue(); assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT body FROM notifications WHERE user_id=?",String.class,user)).contains("마감");
        jdbc.update("UPDATE notification_settings SET event_start=true,deadline_soon=false WHERE user_id=?",user);
        reminders.sendDue(); reminders.sendDue(); assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND body LIKE '%1시간%'",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_start_reminders WHERE user_id=? AND sent_for IS NOT NULL",Integer.class,user)).isEqualTo(1);
        mvc.perform(get("/api/me/reminders/CRAWLED/"+notice+"?kind=EVENT_START").header("Authorization","Bearer "+jwt.issueStudent(user)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
    }

    @Test void eventStartDoesNotSendBeforeOneHourOrForLateOptIn() {
        long source=jdbc.queryForObject("SELECT min(id) FROM crawl_sources",Long.class);
        long notice=jdbc.queryForObject("INSERT INTO crawled_notices(source_id,external_id,title,url,published_at,event_start_at) VALUES (?,?, '행사','https://example.test',now(),now()+INTERVAL '2 hours') RETURNING id",Long.class,source,UUID.randomUUID().toString());
        jdbc.update("INSERT INTO bookmarks(user_id,source,notice_id) VALUES (?,'CRAWLED',?)",user,notice);
        reminders.set(user,"CRAWLED",notice,true,ReminderService.Kind.EVENT_START);
        jdbc.update("UPDATE reminders SET enabled_at=now()-INTERVAL '2 days' WHERE user_id=?",user);
        jdbc.update("UPDATE event_start_reminders SET enabled_at=now()-INTERVAL '2 days' WHERE user_id=?",user);
        reminders.sendDue(); assertThat(count()).isZero();
        jdbc.update("UPDATE crawled_notices SET event_start_at=now()+INTERVAL '50 minutes' WHERE id=?",notice);
        jdbc.update("UPDATE event_start_reminders SET enabled_at=now() WHERE user_id=?",user);
        reminders.sendDue(); assertThat(count()).isZero();
        assertThatThrownBy(()->reminders.set(user,"PERSONAL",id,true,ReminderService.Kind.EVENT_START))
          .isInstanceOf(RuntimeException.class);
    }

    @Test void concurrentWorkersDoNotDuplicate() throws Exception {
        enableDue("PERSONAL",id);
        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            Callable<Integer> work=()->{ start.await(); return reminders.sendDue(); };
            var a=pool.submit(work); var b=pool.submit(work); start.countDown();
            a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
            assertThat(count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void apiOnlyAllowsOwnScheduleAndCreateCanEnableReminder() throws Exception {
        String auth="Bearer "+jwt.issueStudent(user);
        mvc.perform(put("/api/me/reminders/PERSONAL/"+id).header("Authorization",auth)
          .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.tooLate").value(true));
        long other=users.save(new User("DEV",UUID.randomUUID().toString(),"other")).getId();
        mvc.perform(get("/api/me/reminders/PERSONAL/"+id).header("Authorization","Bearer "+jwt.issueStudent(other)))
          .andExpect(status().isBadRequest());
        mvc.perform(post("/api/me/schedules").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
          .content("{\"title\":\"새 일정\",\"at\":\"2030-01-01T00:00:00Z\",\"reminderEnabled\":true}"))
          .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reminders WHERE user_id=? AND enabled",Integer.class,user)).isEqualTo(2);
    }
}
