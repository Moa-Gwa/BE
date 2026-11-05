package com.moagwa.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 메일 서버를 설정하기 전까지 쓰는 기본 구현(운영에서는 SMTP 구현을 @Primary로 둔다). 코드는 로그에 남기지 않는다. 로컬에서는 응답으로 코드를 돌려준다. */
@Slf4j
@Component
class LogVerificationMailSender implements VerificationMailSender {

    @Override
    public void send(String toEmail, String code) {
        log.info("재학생 인증 코드를 발송했다(로그 전용 구현): to={}", toEmail);
    }
}
