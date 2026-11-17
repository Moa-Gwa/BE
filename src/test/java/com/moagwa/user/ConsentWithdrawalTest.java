package com.moagwa.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moagwa.auth.AuthCookies;
import com.moagwa.auth.JwtService;
import com.moagwa.common.PrivacyRetention;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ConsentWithdrawalTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired ConsentRepository consents;
    @Autowired JwtService jwt;
    @Autowired PrivacyRetention retention;

    private User user(boolean consented) {
        var u = new User("DEV", UUID.randomUUID().toString(), "동의");
        u = users.save(u);
        if (consented) consents.agreeCurrent(u.getId());
        return u;
    }

    private String bearer(User u) {
        return "Bearer " + jwt.issueStudent(u, consents.hasCurrentConsent(u.getId()));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test void unconsentedStudentCanOnlyUseConsentApiUntilAgreeing() throws Exception {
        User u = user(false);
        mvc.perform(get("/api/me/profile").header("Authorization", bearer(u))).andExpect(status().isForbidden());
        mvc.perform(post("/api/me/verification/email").header("Authorization", bearer(u))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"student@catholic.ac.kr\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me/consent").header("Authorization", bearer(u)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.version").value(User.TERMS_VERSION));

        String body = "{\"version\":\"%s\",\"terms\":true,\"privacy\":%s}";
        mvc.perform(put("/api/me/consent").header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(User.TERMS_VERSION, false))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/consent").header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted("2000-01-01", true))).andExpect(status().isConflict());
        var response = mvc.perform(put("/api/me/consent").header("Authorization", bearer(u))
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted(User.TERMS_VERSION, true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.required").value(false))
                .andReturn().getResponse();

        Cookie session = response.getCookie(AuthCookies.NAME);
        mvc.perform(get("/api/me/profile").cookie(session)).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM user_consents WHERE user_id = ?", u.getId())).isEqualTo(2);
    }

    @Test void agreeingToTheSameVersionKeepsTheOriginalAuditTimestamp() throws Exception {
        User u = user(true);
        var before = jdbc.queryForObject("SELECT agreed_at FROM user_consents WHERE user_id = ? AND document_type = 'TERMS'",
                java.sql.Timestamp.class, u.getId());

        mvc.perform(put("/api/me/consent").header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"%s\",\"terms\":true,\"privacy\":true}".formatted(User.TERMS_VERSION)))
                .andExpect(status().isOk());

        var after = jdbc.queryForObject("SELECT agreed_at FROM user_consents WHERE user_id = ? AND document_type = 'TERMS'",
                java.sql.Timestamp.class, u.getId());
        assertThat(after).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM user_consents WHERE user_id = ?", u.getId())).isEqualTo(2);
    }

    @Test void withdrawalDeletesPersonalDataAndDetachesPaymentRecords() throws Exception {
        User u = user(true), host = user(true);
        long id = u.getId();
        jdbc.update("INSERT INTO personal_schedules(user_id, title, starts_at) VALUES (?, '일정', now())", id);
        jdbc.update("INSERT INTO device_tokens(user_id, token) VALUES (?, ?)", id, "token-" + id);
        jdbc.update("INSERT INTO student_verifications(user_id, school_email, verified_at) VALUES (?, ?, now())",
                id, id + "@catholic.ac.kr");
        jdbc.update("INSERT INTO refresh_sessions(token_hash, principal_id, role, expires_at) "
                + "VALUES (?, ?, 'STUDENT', now() + INTERVAL '1 day')", "h" + id, id);
        long closed = jdbc.queryForObject("""
                INSERT INTO group_buys (host_id, title, description, unit_price, target_quantity, deadline_at, status)
                VALUES (?, '마감', '', 500, 3, now() - INTERVAL '1 day', 'CLOSED') RETURNING id""", Long.class, host.getId());
        jdbc.update("INSERT INTO group_buy_payments(order_id, group_buy_id, user_id, quantity, amount, status, paid_at) "
                + "VALUES (?, ?, ?, 1, 500, 'PAID', now())", "o-" + id, closed, id);

        mvc.perform(delete("/api/me").header("Authorization", bearer(u))).andExpect(status().isNoContent());

        assertThat(users.findById(id)).isEmpty();
        for (String table : new String[] {"personal_schedules", "device_tokens", "student_verifications"}) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE user_id = ?", id)).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM refresh_sessions WHERE principal_id = ? AND role = 'STUDENT'", id)).isZero();
        assertThat(count("SELECT count(*) FROM group_buy_payments WHERE order_id = ? AND user_id IS NULL", "o-" + id))
                .isOne();
    }

    @Test void withdrawalIsRefusedWhileHostingARecruitingGroupBuy() throws Exception {
        User u = user(true);
        jdbc.update("INSERT INTO group_buys (host_id, title, description, unit_price, target_quantity, deadline_at) "
                + "VALUES (?, '모집', '', 500, 3, now() + INTERVAL '1 day')", u.getId());
        mvc.perform(delete("/api/me").header("Authorization", bearer(u))).andExpect(status().isConflict());
        assertThat(users.findById(u.getId())).isPresent();
    }

    @Test void hostCannotWithdrawWhileACanceledGroupBuyStillHasPaymentsToRefund() throws Exception {
        User host = user(true), buyer = user(true);
        long canceled = jdbc.queryForObject("""
                INSERT INTO group_buys (host_id, title, description, unit_price, target_quantity, deadline_at, status)
                VALUES (?, '취소', '', 500, 3, now() - INTERVAL '1 day', 'CANCELED') RETURNING id""",
                Long.class, host.getId());
        jdbc.update("INSERT INTO group_buy_payments(order_id, group_buy_id, user_id, quantity, amount, status, paid_at) "
                + "VALUES (?, ?, ?, 1, 500, 'PAID', now())", "r-" + canceled, canceled, buyer.getId());
        mvc.perform(delete("/api/me").header("Authorization", bearer(host))).andExpect(status().isConflict());

        jdbc.update("UPDATE group_buy_payments SET status = 'CANCELED' WHERE order_id = ?", "r-" + canceled);
        mvc.perform(delete("/api/me").header("Authorization", bearer(host))).andExpect(status().isNoContent());
    }

    @Test void adminApplicationsWithoutPrivacyConsentAreRejected() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String form = """
                {"organizationName":"단체-%s","university":"가톨릭대학교","department":"학생회","contactName":"담당자",
                 "email":"c-%s@catholic.ac.kr","phone":"010-0000-0000","channelUrl":"https://example.com",
                 "reason":"신청","loginId":"c-%s","privacyAgreed":false}""".formatted(tag, tag, tag);
        mvc.perform(post("/api/admin-applications").contentType(MediaType.APPLICATION_JSON).content(form))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.privacyAgreed").isNotEmpty());
    }

    @Test void retentionDeletesExpiredApplicationsAndDetachedPayments() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String insert = "INSERT INTO admin_applications(organization_name, university, department, contact_name, email, "
                + "phone, channel_url, reason, login_id, status, resolved_at) VALUES ('단체', '대학', '학과', '담당', ?, "
                + "'010-0000-0000', 'https://example.com', '사유', ?, ?, now() - CAST(? AS INTERVAL))";
        jdbc.update(insert, "old-" + tag + "@x.kr", "old-" + tag, "REJECTED", "31 days");
        jdbc.update(insert, "new-" + tag + "@x.kr", "new-" + tag, "REJECTED", "29 days");
        jdbc.update(insert, "ok-" + tag + "@x.kr", "ok-" + tag, "APPROVED", "2 years");
        jdbc.update("INSERT INTO group_buy_payments(order_id, quantity, amount, status, created_at) "
                + "VALUES (?, 1, 500, 'PAID', now() - INTERVAL '6 years')", "p-" + tag);

        retention.deleteExpired();

        assertThat(jdbc.queryForList("SELECT login_id FROM admin_applications WHERE login_id LIKE ?", String.class,
                "%-" + tag)).containsExactly("new-" + tag);
        assertThat(count("SELECT count(*) FROM group_buy_payments WHERE order_id = ?", "p-" + tag)).isZero();
    }
}
