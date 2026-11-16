package com.moagwa.feed;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.moagwa.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "개인 일정", description = "학생 로그인 필요")
@RestController
@RequestMapping("/api/me/schedules")
@RequiredArgsConstructor
class PersonalScheduleController {
    private final PersonalScheduleService service;

    // color 누락=기존 유지, null=기본값 초기화 구분용
    static class Input {
        @NotBlank @Size(max = 100) private String title;
        @NotNull private Instant at;
        private Instant endAt;
        @Size(max = 200) private String location;
        @Size(max = 1000) private String memo;
        private Boolean reminderEnabled;
        @jakarta.validation.constraints.Pattern(regexp="^#[0-9A-Fa-f]{6}$") private String color;
        private boolean colorPresent;

        String title() { return title.strip(); }
        Instant at() { return at; }
        Instant endAt() { return endAt; }
        String location() { return blankToNull(location); }
        String memo() { return blankToNull(memo); }
        Boolean reminderEnabled() { return reminderEnabled; }
        String color() { return color; }
        boolean colorPresent() { return colorPresent; }

        public void setTitle(String title) { this.title = title; }
        public void setAt(Instant at) { this.at = at; }
        public void setEndAt(Instant endAt) { this.endAt = endAt; }
        public void setLocation(String location) { this.location = location; }
        public void setMemo(String memo) { this.memo = memo; }
        public void setReminderEnabled(Boolean reminderEnabled) { this.reminderEnabled = reminderEnabled; }
        @JsonSetter("color")
        public void setColor(String color) { this.color = color; this.colorPresent = true; }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> create(@Valid @RequestBody Input input, Authentication auth) {
        return Map.of("id", service.create(CurrentUser.from(auth).id(), input));
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@PathVariable long id, @Valid @RequestBody Input input, Authentication auth) {
        service.update(CurrentUser.from(auth).id(), id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id, Authentication auth) {
        service.delete(CurrentUser.from(auth).id(), id);
    }
}
