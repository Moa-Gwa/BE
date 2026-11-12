package com.moagwa.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** spring.mail.host가 없을 때 쓰는 구현(로컬). 코드는 로그에 남기지 않고, 로컬에서는 응답(devCode)으로 돌려준다. */
@Slf4j
@Component
class LogVerificationMailSender implements VerificationMailSender {

    @Override
    public void send(String toEmail, String code) {
        log.info("재학생 인증 코드를 발송했다(로그 전용 구현): to={}", toEmail);
    }
}
