package com.moagwa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import jakarta.servlet.http.Cookie;
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

    private Cookie start() {
        var user = users.save(new User("DEV", UUID.randomUUID().toString(), "refresh"));
        var response = new MockHttpServletResponse();
        sessions.start(jwt.issueStudent(user.getId()), response);
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
        jdbc.update("UPDATE refresh_sessions SET expires_at = now() - INTERVAL '1 second'");
        mvc.perform(post("/api/auth/refresh").cookie(token).header("X-Requested-With", "fetch"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }
}
