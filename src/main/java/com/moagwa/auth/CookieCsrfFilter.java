package com.moagwa.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 쿠키로 인증하는 변경 요청(POST/PUT/PATCH/DELETE)에는 X-Requested-With 헤더를 요구한다.
 * 다른 사이트의 폼이나 이미지 태그는 이 헤더를 붙일 수 없고, fetch로 붙이면 CORS 사전 요청에서
 * 허용된 출처만 통과한다. Authorization 헤더로 인증하는 요청은 브라우저가 자동으로 보내지 않으므로 검사하지 않는다.
 */
class CookieCsrfFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Requested-With";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean unsafe = !SAFE_METHODS.contains(request.getMethod());
        boolean cookieAuth = request.getHeader(HttpHeaders.AUTHORIZATION) == null && AuthCookies.read(request) != null;
        String marker = request.getHeader(HEADER);
        if (unsafe && cookieAuth && (marker == null || marker.isBlank())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"message\":\"허용되지 않은 요청입니다.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
