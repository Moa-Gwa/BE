package com.moagwa.feed;

import com.moagwa.auth.CurrentUser;
import com.moagwa.common.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/schedules")
@RequiredArgsConstructor
class PersonalScheduleController {
    private final NamedParameterJdbcTemplate jdbc;
    private final com.moagwa.reminder.ReminderService reminders;

    record Input(@NotBlank @Size(max = 100) String title, @NotNull Instant at,
                 @Size(max = 200) String location, @Size(max = 1000) String memo, Boolean reminderEnabled) {}

    private MapSqlParameterSource params(Input input, Authentication auth) {
        return new MapSqlParameterSource("user", CurrentUser.from(auth).id())
                .addValue("title", input.title().strip())
                .addValue("at", java.sql.Timestamp.from(input.at()))
                .addValue("location", input.location()).addValue("memo", input.memo());
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> create(@Valid @RequestBody Input input, Authentication auth) {
        Long id = jdbc.queryForObject("""
                INSERT INTO personal_schedules(user_id, title, starts_at, location, memo)
                VALUES (:user, :title, :at, :location, :memo) RETURNING id
                """, params(input, auth), Long.class);
        if (Boolean.TRUE.equals(input.reminderEnabled())) reminders.set(CurrentUser.from(auth).id(), "PERSONAL", id, true);
        return Map.of("id", id);
    }

    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@PathVariable long id, @Valid @RequestBody Input input, Authentication auth) {
        int count = jdbc.update("""
                UPDATE personal_schedules SET title = :title, starts_at = :at, location = :location, memo = :memo
                WHERE id = :id AND user_id = :user
                """, params(input, auth).addValue("id", id));
        if (count == 0) throw ApiException.notFound("일정을 찾을 수 없습니다.");
        if (input.reminderEnabled() != null) reminders.set(CurrentUser.from(auth).id(), "PERSONAL", id, input.reminderEnabled());
    }

    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id, Authentication auth) {
        reminders.remove(CurrentUser.from(auth).id(), "PERSONAL", id);
        int count = jdbc.update("DELETE FROM personal_schedules WHERE id = :id AND user_id = :user",
                Map.of("id", id, "user", CurrentUser.from(auth).id()));
        if (count == 0) throw ApiException.notFound("일정을 찾을 수 없습니다.");
    }
}
