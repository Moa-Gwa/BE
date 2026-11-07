package com.moagwa.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.moagwa.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpVerificationMailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpVerificationMailSender sender = new SmtpVerificationMailSender(mailSender, "모아과 <no-reply@moagwa.kr>");

    @Test
    void sendsCodeToSchoolEmail() {
        sender.send("student@catholic.ac.kr", "123456");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("student@catholic.ac.kr");
        assertThat(sent.getValue().getFrom()).isEqualTo("모아과 <no-reply@moagwa.kr>");
        assertThat(sent.getValue().getText()).contains("123456");
    }

    @Test
    void smtpFailureBecomesServiceUnavailable() {
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send("student@catholic.ac.kr", "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("인증 메일을 보내지 못했습니다");
    }
}
