package com.moagwa.feed;

import com.moagwa.auth.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/schedule-colors")
@RequiredArgsConstructor
class ScheduleColorController {
    private final ScheduleColors colors;
    @Schema(name = "ScheduleColorInput")
    record Input(@NotBlank String type, @Pattern(regexp="^#[0-9A-Fa-f]{6}$") String color) {}

    @PutMapping("/{source}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void set(@PathVariable String source, @PathVariable long id, @Valid @RequestBody Input input, Authentication auth) {
        colors.set(CurrentUser.from(auth).id(), source, input.type(), id, input.color());
    }
}
