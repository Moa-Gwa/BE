package com.moagwa.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class AdminSetupMailerTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);

    @SuppressWarnings("unchecked")
    private AdminSetupMailer mailer() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new AdminSetupMailer(provider, "noreply@moagwa.local");
    }

    @Test
    void rejectionMailIncludesReason() {
        assertThat(mailer().sendRejection("council@catholic.ac.kr", "단체 활동을 확인할 수 없음")).isTrue();

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("council@catholic.ac.kr");
        assertThat(sent.getValue().getText()).contains("단체 활동을 확인할 수 없음");
    }

    @Test
    void rejectionMailFailureIsReportedNotThrown() {
        doThrow(new MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));

        assertThat(mailer().sendRejection("council@catholic.ac.kr", "사유")).isFalse();
    }
}
