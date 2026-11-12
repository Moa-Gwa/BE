package com.moagwa.market;

/** 학교 메일로 인증 코드를 보내는 방법. spring.mail.host가 있으면 SMTP, 없으면 로그 구현을 쓴다. */
public interface VerificationMailSender {

    void send(String toEmail, String code);
}
