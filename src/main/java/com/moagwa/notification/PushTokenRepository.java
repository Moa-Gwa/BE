package com.moagwa.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 기기 푸시 토큰과 테스트 발송 기록 접근. 모든 쿼리는 호출자가 넘긴 사용자 id로 한정됨. */
@Repository
@RequiredArgsConstructor
class PushTokenRepository {
    private final NamedParameterJdbcTemplate jdbc;

    /** 다른 계정에 있던 토큰(공용 브라우저)은 현재 사용자로 옮겨짐 */
    void upsert(long user, String token, String userAgent) {
        jdbc.update("""
                INSERT INTO device_tokens (user_id, token, user_agent) VALUES (:u, :t, :ua)
                ON CONFLICT (token) DO UPDATE SET user_id = :u, user_agent = :ua, last_seen_at = now()""",
                new MapSqlParameterSource().addValue("u", user).addValue("t", token).addValue("ua", userAgent));
    }

    void delete(long user, String token) {
        jdbc.update("DELETE FROM device_tokens WHERE token = :t AND user_id = :u",
                new MapSqlParameterSource().addValue("t", token).addValue("u", user));
    }

    boolean isRegistered(long user, String token) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM device_tokens WHERE token = :t AND user_id = :u)",
                new MapSqlParameterSource().addValue("t", token).addValue("u", user), Boolean.class));
    }

    /** 단일 UPSERT로 여러 서버·동시 요청에서도 사용자당 1분에 한 번만 true */
    boolean claimTestAttempt(long user) {
        return jdbc.update("""
                INSERT INTO push_test_attempts (user_id, attempted_at) VALUES (:u, clock_timestamp())
                ON CONFLICT (user_id) DO UPDATE SET attempted_at = clock_timestamp()
                WHERE push_test_attempts.attempted_at <= clock_timestamp() - INTERVAL '1 minute'
                """, new MapSqlParameterSource("u", user)) > 0;
    }
}
