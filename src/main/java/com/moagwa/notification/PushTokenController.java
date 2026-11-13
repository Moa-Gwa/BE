package com.moagwa.notification;

import com.moagwa.auth.CurrentUser;
import com.moagwa.common.exception.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Registers the signed-in student's browser/device for push (FCM). The user id always comes from the token. */
@Tag(name = "푸시 토큰", description = "학생 로그인 필요")
@RestController
@RequestMapping("/api/me/push-tokens")
@RequiredArgsConstructor
class PushTokenController {

    private final NamedParameterJdbcTemplate jdbc;
    private final PushSender pushSender;

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

    /** 이 기기로 테스트 알림 발송. 내 계정에 등록된 토큰만 가능 */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendTest(@Valid @RequestBody PushTokenRequest req, Authentication auth) {
        Boolean registered = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM device_tokens WHERE token = :t AND user_id = :u)",
                new MapSqlParameterSource().addValue("t", req.token()).addValue("u", CurrentUser.from(auth).id()),
                Boolean.class);
        if (!Boolean.TRUE.equals(registered)) {
            throw ApiException.notFound("이 기기는 알림 받을 기기로 등록되어 있지 않습니다. 알림을 다시 켜 주세요.");
        }
        // 단일 UPSERT로 여러 서버·동시 요청에서도 사용자당 1분에 한 번만 발송을 시도함.
        int claimed = jdbc.update("""
                INSERT INTO push_test_attempts (user_id, attempted_at) VALUES (:u, clock_timestamp())
                ON CONFLICT (user_id) DO UPDATE SET attempted_at = clock_timestamp()
                WHERE push_test_attempts.attempted_at <= clock_timestamp() - INTERVAL '1 minute'
                """, new MapSqlParameterSource("u", CurrentUser.from(auth).id()));
        if (claimed == 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "테스트 알림은 1분에 한 번만 보낼 수 있습니다.");
        }
        pushSender.sendTest(req.token());
    }

    record PushTokenRequest(@NotBlank @Size(max = 512) String token) {}
}
