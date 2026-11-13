package com.moagwa.reminder;

import com.moagwa.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/reminders/{source}/{id}")
@RequiredArgsConstructor
class ReminderController {
    private final ReminderService service;
    record Input(@NotNull Boolean enabled) {}
    @GetMapping
    ReminderService.State get(@PathVariable String source,@PathVariable long id,Authentication auth) {
        return service.state(CurrentUser.from(auth).id(),source.toUpperCase(java.util.Locale.ROOT),id);
    }
    @PutMapping
    ReminderService.State set(@PathVariable String source,@PathVariable long id,@Valid @RequestBody Input input,Authentication auth) {
        return service.set(CurrentUser.from(auth).id(),source.toUpperCase(java.util.Locale.ROOT),id,input.enabled());
    }
}
