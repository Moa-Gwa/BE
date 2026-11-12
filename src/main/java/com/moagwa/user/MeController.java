package com.moagwa.user;

import com.moagwa.auth.CurrentUser;
import com.moagwa.user.MeDto.NotificationList;
import com.moagwa.user.MeDto.NotificationSettings;
import com.moagwa.user.MeDto.Profile;
import com.moagwa.user.MeDto.ProfileUpdate;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Everything a signed-in student manages about themself. The user id always comes from the token. */
@Tag(name = "내 정보", description = "학생 로그인 필요")
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
class MeController {

    private final MeService service;

    @GetMapping("/profile")
    Profile profile(Authentication auth) {
        return service.profile(me(auth));
    }

    @PutMapping("/profile")
    Profile updateProfile(@Valid @RequestBody ProfileUpdate req, Authentication auth) {
        return service.updateProfile(me(auth), req);
    }

    @PutMapping("/bookmarks/{source}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void bookmark(@PathVariable String source, @PathVariable long id, Authentication auth) {
        service.bookmark(me(auth), source, id);
    }

    @DeleteMapping("/bookmarks/{source}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unbookmark(@PathVariable String source, @PathVariable long id, Authentication auth) {
        service.unbookmark(me(auth), source, id);
    }

    @GetMapping("/notifications")
    NotificationList notifications(Authentication auth) {
        return service.notifications(me(auth));
    }

    @PutMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@PathVariable long id, Authentication auth) {
        service.markRead(me(auth), id);
    }

    @PutMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markAllRead(Authentication auth) {
        service.markAllRead(me(auth));
    }

    @GetMapping("/notification-settings")
    NotificationSettings settings(Authentication auth) {
        return service.settings(me(auth));
    }

    @PutMapping("/notification-settings")
    NotificationSettings updateSettings(@RequestBody NotificationSettings s, Authentication auth) {
        return service.updateSettings(me(auth), s);
    }

    private static long me(Authentication auth) {
        return CurrentUser.from(auth).id();
    }
}
