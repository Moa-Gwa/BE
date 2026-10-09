package com.moagwa.notification;

import com.moagwa.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Registers the signed-in student's browser/device for push (FCM). The user id always comes from the token. */
@RestController
@RequestMapping("/api/me/push-tokens")
@RequiredArgsConstructor
class PushTokenController {

    private final NamedParameterJdbcTemplate jdbc;

    /** Idempotent. A token seen on another account (shared browser) moves to the current user. */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(@Valid @RequestBody PushTokenRequest req,
                  @RequestHeader(value = "User-Agent", required = false) String userAgent, Authentication auth) {
        jdbc.update("""
                INSERT INTO device_tokens (user_id, token, user_agent) VALUES (:u, :t, :ua)
                ON CONFLICT (token) DO UPDATE SET user_id = :u, user_agent = :ua, last_seen_at = now()""",
                new MapSqlParameterSource().addValue("u", CurrentUser.from(auth).id())
                        .addValue("t", req.token())
                        .addValue("ua", userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length()))));
    }

    /** Only removes the caller's own token; an unknown token is not an error. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unregister(@Valid @RequestBody PushTokenRequest req, Authentication auth) {
        jdbc.update("DELETE FROM device_tokens WHERE token = :t AND user_id = :u",
                new MapSqlParameterSource().addValue("t", req.token()).addValue("u", CurrentUser.from(auth).id()));
    }

    record PushTokenRequest(@NotBlank @Size(max = 512) String token) {}
}
