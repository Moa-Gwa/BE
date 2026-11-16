package com.moagwa.notification;

import com.moagwa.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 학생의 브라우저·기기를 푸시(FCM) 대상으로 등록. 사용자 id는 항상 토큰에서 가져옴 */
@Tag(name = "푸시 토큰", description = "학생 로그인 필요")
@RestController
@RequestMapping("/api/me/push-tokens")
@RequiredArgsConstructor
class PushTokenController {

    private final PushTokenService service;

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(@Valid @RequestBody PushTokenRequest req,
                  @RequestHeader(value = "User-Agent", required = false) String userAgent, Authentication auth) {
        service.register(CurrentUser.from(auth).id(), req.token(), userAgent);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unregister(@Valid @RequestBody PushTokenRequest req, Authentication auth) {
        service.unregister(CurrentUser.from(auth).id(), req.token());
    }

    @PostMapping("/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendTest(@Valid @RequestBody PushTokenRequest req, Authentication auth) {
        service.sendTest(CurrentUser.from(auth).id(), req.token());
    }

    record PushTokenRequest(@NotBlank @Size(max = 512) String token) {}
}
