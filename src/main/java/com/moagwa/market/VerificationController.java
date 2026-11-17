package com.moagwa.market;

import com.moagwa.auth.AuthCookies;
import com.moagwa.auth.CurrentUser;
import com.moagwa.market.VerificationDto.ConfirmRequest;
import com.moagwa.market.VerificationDto.ConfirmResult;
import com.moagwa.market.VerificationDto.EmailRequest;
import com.moagwa.market.VerificationDto.SendResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "재학생 인증", description = "학생 로그인 필요. 인증 완료 시 쿠키가 재학생 권한으로 교체됨")
@RestController
@RequestMapping("/api/me/verification")
@RequiredArgsConstructor
class VerificationController {

    private final VerificationService verification;
    private final AuthCookies cookies;

    @PostMapping("/email")
    SendResult send(@Valid @RequestBody EmailRequest body, Authentication auth) {
        return verification.sendCode(body.email(), me(auth));
    }

    @PostMapping("/confirm")
    ConfirmResult confirm(@Valid @RequestBody ConfirmRequest body, Authentication auth, HttpServletResponse response) {
        // 생활거래 권한(verified)이 담긴 토큰으로 쿠키를 바꾼다.
        cookies.issue(response, verification.confirm(body.code(), me(auth)));
        return new ConfirmResult(true);
    }

    private static long me(Authentication auth) {
        return CurrentUser.from(auth).id();
    }
}
