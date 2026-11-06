package com.moagwa.market;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class StudentVerificationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    record Completed(String schoolEmail, Instant verifiedAt) {
    }

    record Pending(String codeHash, Instant expiresAt, int attempts, String schoolEmail, Instant verifiedAt) {
    }

    Optional<Completed> findCompleted(long userId) {
        return jdbc.query("""
                SELECT school_email, verified_at FROM student_verifications
                 WHERE user_id = :user AND verified_at IS NOT NULL""", new MapSqlParameterSource("user", userId),
                (rs, n) -> new Completed(rs.getString("school_email"), rs.getTimestamp("verified_at").toInstant()))
                .stream().findFirst();
    }

    boolean isEmailVerifiedByOther(String email, long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM student_verifications
                 WHERE lower(school_email) = :email AND verified_at IS NOT NULL AND user_id <> :user""",
                new MapSqlParameterSource().addValue("email", email).addValue("user", userId), Integer.class);
        return count != null && count > 0;
    }

    Optional<Instant> findLastSentAt(long userId) {
        return jdbc.query(
                "SELECT last_sent_at FROM student_verifications WHERE user_id = :user AND last_sent_at IS NOT NULL",
                new MapSqlParameterSource("user", userId), (rs, n) -> rs.getTimestamp("last_sent_at").toInstant())
                .stream().findFirst();
    }

    /** 새 코드를 저장하고 오답 횟수를 초기화한다. */
    void saveCode(long userId, String email, String codeHash, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO student_verifications (user_id, school_email, code_hash, code_expires_at, attempts, last_sent_at)
                VALUES (:user, :email, :hash, :expires, 0, now())
                ON CONFLICT (user_id) DO UPDATE
                   SET school_email = :email, code_hash = :hash, code_expires_at = :expires, attempts = 0,
                       last_sent_at = now()""",
                new MapSqlParameterSource().addValue("user", userId).addValue("email", email)
                        .addValue("hash", codeHash).addValue("expires", Timestamp.from(expiresAt)));
    }

    /** 호출하는 트랜잭션이 끝날 때까지 인증 행을 잠가 오답 횟수를 정확히 센다. */
    Optional<Pending> lock(long userId) {
        return jdbc.query("""
                SELECT code_hash, code_expires_at, attempts, school_email, verified_at
                  FROM student_verifications WHERE user_id = :user FOR UPDATE""",
                new MapSqlParameterSource("user", userId),
                (rs, n) -> new Pending(rs.getString("code_hash"), toInstant(rs.getTimestamp("code_expires_at")),
                        rs.getInt("attempts"), rs.getString("school_email"), toInstant(rs.getTimestamp("verified_at"))))
                .stream().findFirst();
    }

    void increaseAttempts(long userId) {
        jdbc.update("UPDATE student_verifications SET attempts = attempts + 1 WHERE user_id = :user",
                new MapSqlParameterSource("user", userId));
    }

    void markVerified(long userId) {
        MapSqlParameterSource params = new MapSqlParameterSource("user", userId);
        jdbc.update("""
                UPDATE student_verifications SET verified_at = now(), code_hash = NULL, code_expires_at = NULL
                 WHERE user_id = :user""", params);
        jdbc.update("UPDATE users SET verified_at = now() WHERE id = :user", params);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
