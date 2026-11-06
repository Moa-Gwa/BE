package com.moagwa.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 로그인 토큰을 HttpOnly 쿠키로 주고받는다. 브라우저 JS는 토큰을 읽을 수 없다.
 * 운영에서는 FE와 BE를 같은 사이트(예: moagwa.kr, api.moagwa.kr)에 두어야 SameSite=Lax 쿠키가 전송된다.
 */
@Component
public class AuthCookies {

    public static final String NAME = "moagwa_session";

    private final boolean secure;
    private final String sameSite;
    private final String domain;

    AuthCookies(@Value("${moagwa.auth.cookie.secure:true}") boolean secure,
            @Value("${moagwa.auth.cookie.same-site:Lax}") String sameSite,
            @Value("${moagwa.auth.cookie.domain:}") String domain) {
        this.secure = secure;
        this.sameSite = sameSite;
        this.domain = domain.trim();
    }

    public void issue(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(token, JwtService.TTL).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    /** 요청에 담긴 세션 쿠키 값. 없으면 null. */
    static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie build(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(maxAge);
        if (!domain.isEmpty()) {
            cookie.domain(domain);
        }
        return cookie.build();
    }
}
