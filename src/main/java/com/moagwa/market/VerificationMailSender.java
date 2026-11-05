package com.moagwa.market;

/** 학교 메일로 인증 코드를 보내는 방법. 운영에서는 SMTP 구현으로 교체한다. */
public interface VerificationMailSender {

    void send(String toEmail, String code);
}
