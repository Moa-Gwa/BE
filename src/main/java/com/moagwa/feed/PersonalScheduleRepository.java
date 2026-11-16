package com.moagwa.feed;

import java.sql.Timestamp;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 개인 일정 테이블 접근. 모든 쿼리는 호출자가 넘긴 사용자 id로 한정됨. */
@Repository
@RequiredArgsConstructor
class PersonalScheduleRepository {
    private final NamedParameterJdbcTemplate jdbc;

    long insert(long user, PersonalScheduleController.Input input) {
        return jdbc.queryForObject("""
                INSERT INTO personal_schedules(user_id, title, starts_at, ends_at, location, memo)
                VALUES (:user, :title, :at, :endAt, :location, :memo) RETURNING id
                """, params(user, input), Long.class);
    }

    int update(long user, long id, PersonalScheduleController.Input input) {
        return jdbc.update("""
                UPDATE personal_schedules SET title = :title, starts_at = :at, ends_at = :endAt, location = :location, memo = :memo
                WHERE id = :id AND user_id = :user
                """, params(user, input).addValue("id", id));
    }

    int delete(long user, long id) {
        return jdbc.update("DELETE FROM personal_schedules WHERE id = :id AND user_id = :user", Map.of("id", id, "user", user));
    }

    private static MapSqlParameterSource params(long user, PersonalScheduleController.Input input) {
        return new MapSqlParameterSource("user", user)
                .addValue("title", input.title())
                .addValue("at", Timestamp.from(input.at()))
                .addValue("endAt", input.endAt() == null ? null : Timestamp.from(input.endAt()))
                .addValue("location", input.location()).addValue("memo", input.memo());
    }
}
