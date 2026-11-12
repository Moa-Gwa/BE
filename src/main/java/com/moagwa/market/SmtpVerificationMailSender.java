package com.moagwa.market;

import com.moagwa.common.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** spring.mail.host가 설정되면 로그 구현 대신 실제 메일(SMTP, 운영은 AWS SES)로 코드를 보낸다. */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
class SmtpVerificationMailSender implements VerificationMailSender {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpVerificationMailSender(JavaMailSender mailSender, @Value("${moagwa.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String toEmail, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject("[모아과] 재학생 인증 코드");
        message.setText("""
                모아과 재학생 인증 코드입니다.

                인증 코드: %s

                10분 안에 앱에 입력해 주세요.
                본인이 요청하지 않았다면 이 메일을 무시해 주세요.
                """.formatted(code));
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // 호출한 트랜잭션이 롤백되어 저장한 코드와 재발송 제한도 함께 취소된다.
            log.warn("재학생 인증 메일 발송 실패: to={}", toEmail, e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
