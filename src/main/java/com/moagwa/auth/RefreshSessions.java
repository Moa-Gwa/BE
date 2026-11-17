package com.moagwa.auth;

import com.moagwa.admin.AdminRepository;
import com.moagwa.common.exception.ApiException;
import com.moagwa.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshSessions {
    static final Duration TTL = Duration.ofDays(30);
    private static final String INSERT_SQL =
            "INSERT INTO refresh_sessions (token_hash, principal_id, role, expires_at) VALUES (?, ?, ?, ?)";
    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final AuthCookies cookies;
    private final UserRepository users;
    private final AdminRepository admins;
    private final List<String> operatorIds;
    private final SecureRandom random = new SecureRandom();

    RefreshSessions(JdbcTemplate jdbc, JwtService jwt, AuthCookies cookies, UserRepository users,
                    AdminRepository admins, @Value("${moagwa.operator-login-ids:}") List<String> operatorIds) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.cookies = cookies;
        this.users = users;
        this.admins = admins;
        this.operatorIds = operatorIds;
    }

    @Transactional
    public void start(String accessToken, HttpServletResponse response) {
        var claims = jwt.decoder().decode(accessToken);
        String token = randomToken();
        jdbc.update("DELETE FROM refresh_sessions WHERE expires_at <= now()");
        jdbc.update(INSERT_SQL, hash(token),
                Long.parseLong(claims.getSubject()), claims.getClaimAsString("role"),
                java.sql.Timestamp.from(Instant.now().plus(TTL)));
        cookies.issue(response, accessToken);
        cookies.issueRefresh(response, token, TTL);
    }

    @Transactional
    public void refresh(HttpServletRequest request, HttpServletResponse response) {
        String token = AuthCookies.read(request, AuthCookies.REFRESH_NAME);
        if (token == null) throw unauthorized();
        // 이전 토큰 소비와 새 토큰 저장은 한 트랜잭션에서 수행한다.
        var sessions = jdbc.query("DELETE FROM refresh_sessions WHERE token_hash = ? AND expires_at > now() "
                        + "RETURNING principal_id, role, expires_at",
                (rs, row) -> new Session(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()), hash(token));
        if (sessions.isEmpty()) throw unauthorized();
        var session = sessions.get(0);
        String access;
        if (session.role().equals("STUDENT")) {
            var user = users.findById(session.id()).orElseThrow(RefreshSessions::unauthorized);
            access = jwt.issueStudent(user);
        } else {
            var admin = admins.findById(session.id()).filter(a -> a.isActivated())
                    .orElseThrow(RefreshSessions::unauthorized);
            boolean operator = admin.isOperator() || operatorIds.stream().map(String::trim)
                    .anyMatch(admin.getLoginId()::equals);
            access = jwt.issueAdmin(admin.getId(), admin.getOrganization().getId(), operator);
        }
        Duration remaining = Duration.between(Instant.now(), session.expiresAt());
        // Max-Age는 초 단위이므로 1초 미만이면 쿠키를 발급하지 않고 소비도 롤백함
        if (remaining.compareTo(Duration.ofSeconds(1)) < 0) throw unauthorized();
        String next = randomToken();
        jdbc.update(INSERT_SQL, hash(next), session.id(), session.role(),
                java.sql.Timestamp.from(session.expiresAt()));
        cookies.issue(response, access);
        cookies.issueRefresh(response, next, remaining);
    }

    public void revoke(HttpServletRequest request) {
        String token = AuthCookies.read(request, AuthCookies.REFRESH_NAME);
        if (token != null) jdbc.update("DELETE FROM refresh_sessions WHERE token_hash = ?", hash(token));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요.");
    }

    private record Session(long id, String role, Instant expiresAt) {}
}
