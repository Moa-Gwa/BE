package com.moagwa.market;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moagwa.auth.CurrentUser;
import com.moagwa.auth.JwtService;
import com.moagwa.common.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 학교 메일로 재학생 인증을 한다. 인증을 마치면 생활거래 API를 쓸 수 있는 토큰(verified)을 새로 발급한다. */
@RestController
@RequestMapping("/api/me/verification")
@RequiredArgsConstructor
class VerificationController {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration RESEND_GAP = Duration.ofMinutes(1);

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final VerificationMailSender mail;
    private final SecureRandom random = new SecureRandom();

    @Value("${moagwa.verification.allowed-email-domain:catholic.ac.kr}")
    private String allowedDomain;

    /** true이면 코드를 응답에도 담는다. 메일 서버가 없는 로컬·시연 환경 전용이다. */
    @Value("${moagwa.verification.expose-code:false}")
    private boolean exposeCode;

    @GetMapping
    @Transactional(readOnly = true)
    Status status(Authentication auth) {
        long me = CurrentUser.from(auth).id();
        List<Status> rows = jdbc.query("""
                SELECT school_email, verified_at FROM student_verifications
                 WHERE user_id = :u AND verified_at IS NOT NULL""", new MapSqlParameterSource("u", me),
                (rs, i) -> new Status(true, rs.getTimestamp("verified_at").toInstant().toString(),
                        mask(rs.getString("school_email"))));
        return rows.isEmpty() ? new Status(false, null, null) : rows.get(0);
    }

    @PostMapping("/email")
    @Transactional
    SendResult send(@Valid @RequestBody EmailRequest body, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        if (email.indexOf('@') < 1 || email.indexOf('@') != email.lastIndexOf('@')
                || !email.endsWith("@" + allowedDomain)) {
            throw ApiException.badRequest("@" + allowedDomain + " 학교 메일만 인증할 수 있습니다.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("u", me).addValue("e", email);
        if (count("SELECT count(*) FROM student_verifications WHERE user_id = :u AND verified_at IS NOT NULL", p) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 재학생 인증을 마쳤습니다.");
        }
        if (count("""
                SELECT count(*) FROM student_verifications
                 WHERE lower(school_email) = :e AND verified_at IS NOT NULL AND user_id <> :u""", p) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 다른 계정에 연결된 학교 메일입니다.");
        }
        List<Timestamp> lastSent = jdbc.query(
                "SELECT last_sent_at FROM student_verifications WHERE user_id = :u AND last_sent_at IS NOT NULL", p,
                (rs, i) -> rs.getTimestamp("last_sent_at"));
        if (!lastSent.isEmpty() && lastSent.get(0).toInstant().isAfter(Instant.now().minus(RESEND_GAP))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "인증 코드는 1분에 한 번만 받을 수 있습니다.");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        p.addValue("h", encoder.encode(code)).addValue("x", Timestamp.from(Instant.now().plus(CODE_TTL)));
        jdbc.update("""
                INSERT INTO student_verifications (user_id, school_email, code_hash, code_expires_at, attempts, last_sent_at)
                VALUES (:u, :e, :h, :x, 0, now())
                ON CONFLICT (user_id) DO UPDATE
                   SET school_email = :e, code_hash = :h, code_expires_at = :x, attempts = 0, last_sent_at = now()""", p);
        mail.send(email, code);
        return new SendResult((int) CODE_TTL.toSeconds(), exposeCode ? code : null);
    }

    /** 틀린 시도 횟수는 예외가 나도 남아야 하므로 ApiException에서는 롤백하지 않는다. */
    @PostMapping("/confirm")
    @Transactional(noRollbackFor = ApiException.class)
    ConfirmResult confirm(@Valid @RequestBody ConfirmRequest body, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        MapSqlParameterSource p = new MapSqlParameterSource("u", me);
        List<Pending> rows = jdbc.query("""
                SELECT code_hash, code_expires_at, attempts, school_email, verified_at
                  FROM student_verifications WHERE user_id = :u FOR UPDATE""", p,
                (rs, i) -> new Pending(rs.getString("code_hash"), rs.getTimestamp("code_expires_at"),
                        rs.getInt("attempts"), rs.getString("school_email"), rs.getTimestamp("verified_at")));
        if (rows.isEmpty() || (rows.get(0).hash() == null && rows.get(0).verifiedAt() == null)) {
            throw ApiException.badRequest("먼저 인증 코드를 받아 주세요.");
        }
        Pending row = rows.get(0);
        if (row.verifiedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 재학생 인증을 마쳤습니다.");
        }
        if (row.expiresAt() == null || row.expiresAt().toInstant().isBefore(Instant.now())) {
            throw ApiException.badRequest("인증 코드가 만료되었습니다. 새 코드를 받아 주세요.");
        }
        if (row.attempts() >= MAX_ATTEMPTS) {
            throw ApiException.badRequest("코드를 5번 틀렸습니다. 새 코드를 받아 주세요.");
        }
        if (!encoder.matches(body.code(), row.hash())) {
            jdbc.update("UPDATE student_verifications SET attempts = attempts + 1 WHERE user_id = :u", p);
            throw ApiException.badRequest("인증 코드가 올바르지 않습니다.");
        }
        p.addValue("e", row.email().toLowerCase(Locale.ROOT));
        if (count("""
                SELECT count(*) FROM student_verifications
                 WHERE lower(school_email) = :e AND verified_at IS NOT NULL AND user_id <> :u""", p) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 다른 계정에 연결된 학교 메일입니다.");
        }
        jdbc.update("""
                UPDATE student_verifications SET verified_at = now(), code_hash = NULL, code_expires_at = NULL
                 WHERE user_id = :u""", p);
        jdbc.update("UPDATE users SET verified_at = now() WHERE id = :u", p);
        return new ConfirmResult(true, jwt.issueStudent(me, true));
    }

    private int count(String sql, MapSqlParameterSource p) {
        Integer n = jdbc.queryForObject(sql, p, Integer.class);
        return n == null ? 0 : n;
    }

    /** hyun1234@catholic.ac.kr → hyu***@catholic.ac.kr */
    static String mask(String email) {
        int at = email.indexOf('@');
        String id = email.substring(0, at);
        return id.substring(0, Math.min(3, id.length())) + "***" + email.substring(at);
    }

    private record Pending(String hash, Timestamp expiresAt, int attempts, String email, Timestamp verifiedAt) {
    }

    record EmailRequest(@NotBlank @Size(max = 200) String email) {
    }

    record ConfirmRequest(@NotBlank @Pattern(regexp = "^\\d{6}$", message = "6자리 숫자여야 합니다.") String code) {
    }

    record Status(boolean verified, String verifiedAt, String schoolEmail) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SendResult(int expiresInSeconds, String devCode) {
    }

    record ConfirmResult(boolean verified, String token) {
    }
}
