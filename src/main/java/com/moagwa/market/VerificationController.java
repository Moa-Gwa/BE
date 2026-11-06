package com.moagwa.market;

import com.moagwa.auth.CurrentUser;
import com.moagwa.market.VerificationDto.ConfirmRequest;
import com.moagwa.market.VerificationDto.ConfirmResult;
import com.moagwa.market.VerificationDto.EmailRequest;
import com.moagwa.market.VerificationDto.SendResult;
import com.moagwa.market.VerificationDto.Status;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/verification")
@RequiredArgsConstructor
class VerificationController {

    private final VerificationService verification;

    @GetMapping
    Status status(Authentication auth) {
        return verification.status(me(auth));
    }

    @PostMapping("/email")
    SendResult send(@Valid @RequestBody EmailRequest body, Authentication auth) {
        return verification.sendCode(body.email(), me(auth));
    }

    @PostMapping("/confirm")
    ConfirmResult confirm(@Valid @RequestBody ConfirmRequest body, Authentication auth) {
        return verification.confirm(body.code(), me(auth));
    }

    private static long me(Authentication auth) {
        return CurrentUser.from(auth).id();
    }
}
