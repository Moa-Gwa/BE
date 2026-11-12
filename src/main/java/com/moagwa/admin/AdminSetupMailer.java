package com.moagwa.admin;

import com.moagwa.common.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 승인된 단체 담당자에게 비밀번호 설정 링크를 메일로 보냄. 메일 설정(spring.mail.host)이 없으면 보내지 않음 */
@Slf4j
@Component
class AdminSetupMailer {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;

    AdminSetupMailer(ObjectProvider<JavaMailSender> mailSender,
                     @Value("${moagwa.mail.from:noreply@moagwa.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /** 메일을 보냈으면 true, 메일 설정이 없으면 false */
    boolean send(String toEmail, String setupLink) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject("[모아과] 관리자 계정 설정");
        message.setText("""
                모아과 단체 관리자 계정이 승인되었습니다.

                아래 링크에서 비밀번호를 설정해 주세요. (1시간 동안 유효)
                %s
                """.formatted(setupLink));
        try {
            sender.send(message);
            return true;
        } catch (MailException e) {
            // 호출한 승인 트랜잭션이 롤백되어 신청은 대기 상태로 남는다.
            log.warn("관리자 계정 설정 메일 발송 실패: to={}", toEmail, e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "설정 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
