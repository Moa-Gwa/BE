package com.moagwa.notification;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import com.moagwa.common.exception.ApiException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the web push for notifications that were just created. Runs after the commit and off the request thread,
 * so a push failure never rolls back or slows down publishing a notice. The message is data-only; the service
 * worker decides how to show it (title/body/url).
 */
@Slf4j
@Component
class PushSender {

    private static final int BATCH_LIMIT = 500; // FCM sendEach limit
    private static final String TEST_TITLE = "모아과 테스트 알림";
    private static final String TEST_BODY = "이 기기에서 푸시 알림을 받을 수 있어요.";
    private static final String TEST_URL = "/my";

    private final ObjectProvider<FirebaseMessaging> firebase;
    private final NamedParameterJdbcTemplate jdbc;

    PushSender(ObjectProvider<FirebaseMessaging> firebase, NamedParameterJdbcTemplate jdbc) {
        this.firebase = firebase;
        this.jdbc = jdbc;
        log.info(firebase.getIfAvailable() != null ? "FCM push enabled"
                : "FCM push disabled (set FIREBASE_CREDENTIALS_PATH to enable)");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onPushRequested(PushRequested e) {
        FirebaseMessaging messaging = firebase.getIfAvailable();
        if (messaging == null || e.userIds().isEmpty()) {
            return; // FIREBASE_CREDENTIALS_PATH not configured
        }
        List<String> tokens = jdbc.queryForList("SELECT token FROM device_tokens WHERE user_id IN (:ids)",
                new MapSqlParameterSource("ids", e.userIds()), String.class);
        for (int from = 0; from < tokens.size(); from += BATCH_LIMIT) {
            List<String> chunk = tokens.subList(from, Math.min(from + BATCH_LIMIT, tokens.size()));
            send(messaging, chunk, e);
        }
    }

    private void send(FirebaseMessaging messaging, List<String> tokens, PushRequested e) {
        List<Message> messages = new ArrayList<>();
        for (String token : tokens) {
            messages.add(message(token, e.title(), e.body(), e.url()));
        }
        try {
            BatchResponse result = messaging.sendEach(messages);
            List<SendResponse> responses = result.getResponses();
            for (int i = 0; i < responses.size(); i++) {
                if (!responses.get(i).isSuccessful()) {
                    handleFailure(tokens.get(i), responses.get(i).getException());
                }
            }
            log.info("Push sent: {} ok, {} failed ({})", result.getSuccessCount(), result.getFailureCount(), e.url());
        } catch (FirebaseMessagingException | RuntimeException ex) {
            log.warn("Push batch failed ({})", e.url(), ex);
        }
    }

    /** 한 기기로 테스트 알림을 바로 보냄. 결과를 화면에 알려야 해서 요청 스레드에서 동기로 처리함 */
    void sendTest(String token) {
        FirebaseMessaging messaging = firebase.getIfAvailable();
        if (messaging == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "푸시 알림이 아직 설정되지 않았습니다.");
        }
        try {
            messaging.send(message(token, TEST_TITLE, TEST_BODY, TEST_URL));
        } catch (FirebaseMessagingException ex) {
            if (stale(ex.getMessagingErrorCode())) {
                deleteToken(token);
                throw new ApiException(HttpStatus.GONE, "이 기기의 알림 등록이 만료되었습니다. 알림을 다시 켜 주세요.");
            }
            log.warn("Test push failed: {}", ex.getMessagingErrorCode(), ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "테스트 알림을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** data 전용 메시지. 표시 방식은 서비스 워커가 정함 */
    private static Message message(String token, String title, String body, String url) {
        return Message.builder()
                .setToken(token)
                .putData("title", title)
                .putData("body", body == null ? "" : body)
                .putData("url", url)
                .build();
    }

    /** Expired/invalid registrations are removed so we stop sending to them. */
    private void handleFailure(String token, FirebaseMessagingException ex) {
        MessagingErrorCode code = ex == null ? null : ex.getMessagingErrorCode();
        if (stale(code)) {
            deleteToken(token);
        } else {
            log.warn("Push failed for one token: {}", code, ex);
        }
    }

    private static boolean stale(MessagingErrorCode code) {
        return code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT;
    }

    private void deleteToken(String token) {
        jdbc.update("DELETE FROM device_tokens WHERE token = :t", new MapSqlParameterSource("t", token));
        log.info("Removed stale push token");
    }
}
