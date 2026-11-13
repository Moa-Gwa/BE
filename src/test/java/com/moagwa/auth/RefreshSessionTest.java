package com.moagwa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.moagwa.admin.Admin;
import com.moagwa.admin.AdminRepository;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RefreshSessionTest {
    @Autowired RefreshSessions sessions;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminRepository admins;
    @Autowired OrganizationRepository organizations;

    private Cookie start() {
        var user = users.save(new User("DEV", UUID.randomUUID().toString(), "refresh"));
        return start(jwt.issueStudent(user.getId()));
    }

    private Cookie start(String accessToken) {
        var response = new MockHttpServletResponse();
        sessions.start(accessToken, response);
        var cookie = response.getCookie(AuthCookies.REFRESH_NAME);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isEqualTo(30 * 86400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE token_hash = ?", Integer.class,
                cookie.getValue())).isZero();
        return cookie;
    }

    @Test void rotatesAndRejectsOldToken() throws Exception {
        var old = start();
        var result = mvc.perform(post("/api/auth/refresh").cookie(old).header("X-Requested-With", "fetch"))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        var next = result.getCookie(AuthCookies.REFRESH_NAME);
        assertThat(next.getValue()).isNotEqualTo(old.getValue());
        mvc.perform(get("/api/me/profile").cookie(result.getCookie(AuthCookies.NAME))).andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh").cookie(old).header("X-Requested-With", "fetch"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(next).header("X-Requested-With", "fetch"))
                .andExpect(status().isNoContent());
    }

    @Test void lessThanOneSecondRemainingDoesNotIssueCookiesOrRotate() throws Exception {
        var token = start();
        String hash = hash(token);
        jdbc.update("UPDATE refresh_sessions SET expires_at = clock_timestamp() + INTERVAL '900 milliseconds' "
                + "WHERE token_hash = ?", hash);
        var response = mvc.perform(post("/api/auth/refresh").cookie(token).header("X-Requested-With", "fetch"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE token_hash = ?",
                Integer.class, hash)).isOne();
    }

    @Test void logoutRevokesRefreshToken() throws Exception {
        var token = start();
        mvc.perform(post("/api/auth/logout").cookie(token).header("X-Requested-With", "fetch"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").cookie(token).header("X-Requested-With", "fetch"))
                .andExpect(status().isUnauthorized());
    }

    @Test void refreshRequiresCsrfHeaderAndRejectsExpiredOrMissingTokens() throws Exception {
        var token = start();
        mvc.perform(post("/api/auth/refresh").cookie(token)).andExpect(status().isForbidden());
        jdbc.update("UPDATE refresh_sessions SET expires_at = now() - INTERVAL '1 second' WHERE token_hash = ?",
                hash(token));
        mvc.perform(post("/api/auth/refresh").cookie(token).header("X-Requested-With", "fetch"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test void adminRefreshKeepsOperatorAndRejectsDeactivatedAccount() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Organization org = organizations.save(new Organization("갱신학생회-" + tag, OrganizationType.STUDENT_GOVERNMENT));
        Admin admin = admins.save(new Admin("refresh-" + tag, "hash", "관리자", org));
        jdbc.update("UPDATE admins SET role = 'OPERATOR' WHERE id = ?", admin.getId());
        var token = start(jwt.issueAdmin(admin.getId(), org.getId(), true));

        var result = mvc.perform(post("/api/auth/refresh").cookie(token).header("X-Requested-With", "fetch"))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        var access = jwt.decoder().decode(result.getCookie(AuthCookies.NAME).getValue());
        assertThat(access.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(access.getClaimAsBoolean("operator")).isTrue();

        // 비활성화된 관리자는 갱신 불가
        jdbc.update("UPDATE admins SET activated = false WHERE id = ?", admin.getId());
        mvc.perform(post("/api/auth/refresh").cookie(result.getCookie(AuthCookies.REFRESH_NAME))
                        .header("X-Requested-With", "fetch"))
                .andExpect(status().isUnauthorized());
    }

    private static String hash(Cookie token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getValue().getBytes(StandardCharsets.UTF_8)));
    }
}
