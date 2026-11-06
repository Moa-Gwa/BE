package com.moagwa.market;

import com.moagwa.auth.JwtService;
import com.moagwa.common.exception.ApiException;
import com.moagwa.market.StudentVerificationRepository.Pending;
import com.moagwa.market.VerificationDto.ConfirmResult;
import com.moagwa.market.VerificationDto.SendResult;
import com.moagwa.market.VerificationDto.Status;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 학교 메일로 재학생 인증을 한다. 인증을 마치면 생활거래 API를 쓸 수 있는 토큰(verified)을 새로 발급한다. */
@Service
@RequiredArgsConstructor
class VerificationService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration RESEND_GAP = Duration.ofMinutes(1);

    private final StudentVerificationRepository verifications;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final VerificationMailSender mail;
    private final SecureRandom random = new SecureRandom();

    @Value("${moagwa.verification.allowed-email-domain:catholic.ac.kr}")
    private String allowedDomain;

    /** true이면 코드를 응답에도 담는다. 메일 서버가 없는 로컬·시연 환경 전용이다. */
    @Value("${moagwa.verification.expose-code:false}")
    private boolean exposeCode;

    @Transactional(readOnly = true)
    Status status(long me) {
        return verifications.findCompleted(me)
                .map(done -> new Status(true, done.verifiedAt().toString(), mask(done.schoolEmail())))
                .orElseGet(() -> new Status(false, null, null));
    }

    @Transactional
    SendResult sendCode(String rawEmail, long me) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        requireSchoolEmail(email);
        if (verifications.findCompleted(me).isPresent()) {
            throw alreadyVerified();
        }
        if (verifications.isEmailVerifiedByOther(email, me)) {
            throw emailTaken();
        }
        Optional<Instant> lastSent = verifications.findLastSentAt(me);
        if (lastSent.isPresent() && lastSent.get().isAfter(Instant.now().minus(RESEND_GAP))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "인증 코드는 1분에 한 번만 받을 수 있습니다.");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        verifications.saveCode(me, email, encoder.encode(code), Instant.now().plus(CODE_TTL));
        mail.send(email, code);
        return new SendResult((int) CODE_TTL.toSeconds(), exposeCode ? code : null);
    }

    /** 틀린 시도 횟수는 예외가 나도 남아야 하므로 ApiException에서는 롤백하지 않는다. */
    @Transactional(noRollbackFor = ApiException.class)
    ConfirmResult confirm(String code, long me) {
        Pending pending = verifications.lock(me).orElseThrow(VerificationService::codeNotRequested);
        if (pending.verifiedAt() != null) {
            throw alreadyVerified();
        }
        if (pending.codeHash() == null) {
            throw codeNotRequested();
        }
        if (pending.expiresAt() == null || pending.expiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("인증 코드가 만료되었습니다. 새 코드를 받아 주세요.");
        }
        if (pending.attempts() >= MAX_ATTEMPTS) {
            throw ApiException.badRequest("코드를 5번 틀렸습니다. 새 코드를 받아 주세요.");
        }
        if (!encoder.matches(code, pending.codeHash())) {
            verifications.increaseAttempts(me);
            throw ApiException.badRequest("인증 코드가 올바르지 않습니다.");
        }
        if (verifications.isEmailVerifiedByOther(pending.schoolEmail().toLowerCase(Locale.ROOT), me)) {
            throw emailTaken();
        }
        verifications.markVerified(me);
        return new ConfirmResult(true, jwt.issueStudent(me, true));
    }

    private void requireSchoolEmail(String email) {
        if (email.indexOf('@') < 1 || email.indexOf('@') != email.lastIndexOf('@')
                || !email.endsWith("@" + allowedDomain)) {
            throw ApiException.badRequest("@" + allowedDomain + " 학교 메일만 인증할 수 있습니다.");
        }
    }

    /** hyun1234@catholic.ac.kr → hyu***@catholic.ac.kr */
    private static String mask(String email) {
        int at = email.indexOf('@');
        String id = email.substring(0, at);
        return id.substring(0, Math.min(3, id.length())) + "***" + email.substring(at);
    }

    private static ApiException codeNotRequested() {
        return ApiException.badRequest("먼저 인증 코드를 받아 주세요.");
    }

    private static ApiException alreadyVerified() {
        return new ApiException(HttpStatus.CONFLICT, "이미 재학생 인증을 마쳤습니다.");
    }

    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "이미 다른 계정에 연결된 학교 메일입니다.");
    }
}
