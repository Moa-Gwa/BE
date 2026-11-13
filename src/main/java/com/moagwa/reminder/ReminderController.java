package com.moagwa.reminder;

import com.moagwa.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 저장한 공지 마감·개인 일정 시작 24시간 전 리마인더 설정 */
@RestController
@RequestMapping("/api/me/reminders/{source}/{id}")
@RequiredArgsConstructor
class ReminderController {

    private final ReminderService service;

    record Input(@NotNull Boolean enabled) {}

    @GetMapping
    ReminderService.State get(@PathVariable String source, @PathVariable long id, Authentication auth) {
        return service.state(CurrentUser.from(auth).id(), source.toUpperCase(Locale.ROOT), id);
    }

    @PutMapping
    ReminderService.State set(@PathVariable String source, @PathVariable long id,
                              @Valid @RequestBody Input input, Authentication auth) {
        return service.set(CurrentUser.from(auth).id(), source.toUpperCase(Locale.ROOT), id, input.enabled());
    }
}
