package com.moagwa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moagwa.admin.Admin;
import com.moagwa.admin.AdminRepository;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** 로그인 토큰을 HttpOnly 쿠키로 주고받는 흐름과 쿠키 인증의 CSRF 방어를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthCookieTest {

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @Autowired OrganizationRepository organizations;
    @Autowired AdminRepository admins;

    private String loginId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Organization org = organizations.save(new Organization("쿠키학생회-" + tag, OrganizationType.STUDENT_GOVERNMENT));
        loginId = "cookie-" + tag;
        admins.save(new Admin(loginId, encoder.encode("pw-1234"), "관리자", org));
    }

    private Cookie login() throws Exception {
        String setCookie = mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"pw-1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(AuthCookies.NAME + "=")
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/", "Max-Age=43200");
        String value = setCookie.substring(AuthCookies.NAME.length() + 1, setCookie.indexOf(';'));
        return new Cookie(AuthCookies.NAME, value);
    }

    @Test
    void sessionCookieAuthenticatesRequests() throws Exception {
        Cookie session = login();
        mvc.perform(get("/api/admin/notices").cookie(session)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/notices")).andExpect(status().isUnauthorized());
    }

    @Test
    void cookieAuthenticatedWritesNeedTheRequestedWithHeader() throws Exception {
        Cookie session = login();
        mvc.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(post("/api/auth/logout").cookie(session).header("X-Requested-With", "fetch"))
                .andExpect(status().isNoContent());
    }

    @Test
    void logoutExpiresTheCookie() throws Exception {
        mvc.perform(post("/api/auth/logout").header("X-Requested-With", "fetch"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.startsWith(AuthCookies.NAME + "=;"),
                                org.hamcrest.Matchers.containsString("Max-Age=0"))));
    }

    @Test
    void staleCookieIsIgnoredOnPublicEndpointsButNotTrustedOnProtectedOnes() throws Exception {
        Cookie stale = new Cookie(AuthCookies.NAME, "not-a-valid-token");
        mvc.perform(get("/api/feed").cookie(stale)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/notices").cookie(stale)).andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightAllowsCredentialsForTheFrontendOrigin() throws Exception {
        mvc.perform(options("/api/me/profile")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-requested-with"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mvc.perform(options("/api/me/profile")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
                .andExpect(status().isForbidden());
    }
}
