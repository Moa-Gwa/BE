package com.moagwa.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Authorization 헤더(앱·테스트용)를 먼저 보고, 없으면 세션 쿠키를 읽는다.
 * 만료되었거나 잘못된 쿠키는 익명 요청으로 취급한다. 브라우저는 공개 API에도 쿠키를 보내므로,
 * 낡은 쿠키 때문에 피드 조회나 로그인이 401로 막히지 않게 하기 위함이다.
 */
class AuthTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
    private final JwtDecoder decoder;

    AuthTokenResolver(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String fromHeader = header.resolve(request);
        if (fromHeader != null) {
            return fromHeader;
        }
        String fromCookie = AuthCookies.read(request);
        if (fromCookie == null) {
            return null;
        }
        try {
            decoder.decode(fromCookie);
            return fromCookie;
        } catch (JwtException e) {
            return null;
        }
    }
}
