package com.moagwa.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.moagwa.auth.JwtService;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class PushTestSendTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockBean FirebaseMessaging messaging;

    private User me;
    private User other;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        me = users.save(new User("DEV", "me-" + tag, "me"));
        other = users.save(new User("DEV", "other-" + tag, "other"));
        token = "fcm-" + tag;
        call(put("/api/me/push-tokens"), me, token).andExpect(status().isNoContent());
    }

    @Test
    void registeredDeviceReceivesTestPush() throws Exception {
        call(post("/api/me/push-tokens/test"), me, token).andExpect(status().isNoContent());

        verify(messaging).send(any(Message.class));
    }

    @Test
    void cannotSendToUnregisteredOrSomeoneElsesDevice() throws Exception {
        call(post("/api/me/push-tokens/test"), other, token).andExpect(status().isNotFound());
        call(post("/api/me/push-tokens/test"), me, "unknown-token").andExpect(status().isNotFound());

        verify(messaging, never()).send(any(Message.class));
    }

    @Test
    void expiredTokenIsRemovedAndReportedAsGone() throws Exception {
        FirebaseMessagingException expired = mock(FirebaseMessagingException.class);
        when(expired.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(messaging.send(any(Message.class))).thenThrow(expired);

        call(post("/api/me/push-tokens/test"), me, token).andExpect(status().isGone());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_tokens WHERE token = ?", Integer.class, token))
                .isZero();
    }

    @Test
    void otherFcmFailureKeepsTheToken() throws Exception {
        FirebaseMessagingException unavailable = mock(FirebaseMessagingException.class);
        when(unavailable.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);
        when(messaging.send(any(Message.class))).thenThrow(unavailable);

        call(post("/api/me/push-tokens/test"), me, token).andExpect(status().isBadGateway());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_tokens WHERE token = ?", Integer.class, token))
                .isOne();
    }

    @Test
    void rateLimitIsPerUserAcrossTokensAndAllowsSendingAfterOneMinute() throws Exception {
        call(post("/api/me/push-tokens/test"), me, token).andExpect(status().isNoContent());
        String secondToken = token + "-second";
        call(put("/api/me/push-tokens"), me, secondToken).andExpect(status().isNoContent());
        call(post("/api/me/push-tokens/test"), me, secondToken).andExpect(status().isTooManyRequests());

        String otherToken = token + "-other";
        call(put("/api/me/push-tokens"), other, otherToken).andExpect(status().isNoContent());
        call(post("/api/me/push-tokens/test"), other, otherToken).andExpect(status().isNoContent());

        jdbc.update("UPDATE push_test_attempts SET attempted_at = clock_timestamp() - INTERVAL '61 seconds' WHERE user_id = ?",
                me.getId());
        call(post("/api/me/push-tokens/test"), me, secondToken).andExpect(status().isNoContent());
        verify(messaging, times(3)).send(any(Message.class));
    }

    @Test
    void failedSendStillConsumesTheInterval() throws Exception {
        FirebaseMessagingException unavailable = mock(FirebaseMessagingException.class);
        when(unavailable.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);
        when(messaging.send(any(Message.class))).thenThrow(unavailable);

        call(post("/api/me/push-tokens/test"), me, token).andExpect(status().isBadGateway());
        call(post("/api/me/push-tokens/test"), me, token).andExpect(status().isTooManyRequests());
        verify(messaging).send(any(Message.class));
    }

    @Test
    void concurrentRequestsOnlySendOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        var ready = new CountDownLatch(6);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Integer>>();
        try {
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                    return call(post("/api/me/push-tokens/test"), me, token)
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(204, 429);
            assertThat(statuses.stream().filter(status -> status == 204).count()).isEqualTo(1);
            verify(messaging).send(any(Message.class));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                               User user, String pushToken) throws Exception {
        return mvc.perform(req
                .header("Authorization", "Bearer " + jwt.issueStudent(user.getId(), true))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + pushToken + "\"}"));
    }
}
