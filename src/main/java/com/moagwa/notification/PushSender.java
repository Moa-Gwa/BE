package com.moagwa.notification;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
        String url = "/notices/" + e.source().toLowerCase() + "/" + e.noticeId();
        for (int from = 0; from < tokens.size(); from += BATCH_LIMIT) {
            List<String> chunk = tokens.subList(from, Math.min(from + BATCH_LIMIT, tokens.size()));
            send(messaging, chunk, e, url);
        }
    }

    private void send(FirebaseMessaging messaging, List<String> tokens, PushRequested e, String url) {
        List<Message> messages = new ArrayList<>();
        for (String token : tokens) {
            messages.add(Message.builder().setToken(token)
                    .putData("title", e.title()).putData("body", e.body() == null ? "" : e.body())
                    .putData("url", url).build());
        }
        try {
            BatchResponse result = messaging.sendEach(messages);
            List<SendResponse> responses = result.getResponses();
            for (int i = 0; i < responses.size(); i++) {
                if (!responses.get(i).isSuccessful()) {
                    handleFailure(tokens.get(i), responses.get(i).getException());
                }
            }
            log.info("Push sent: {} ok, {} failed (notice {} {})", result.getSuccessCount(), result.getFailureCount(),
                    e.source(), e.noticeId());
        } catch (FirebaseMessagingException | RuntimeException ex) {
            log.warn("Push batch failed for notice {} {}", e.source(), e.noticeId(), ex);
        }
    }

    /** Expired/invalid registrations are removed so we stop sending to them. */
    private void handleFailure(String token, FirebaseMessagingException ex) {
        MessagingErrorCode code = ex == null ? null : ex.getMessagingErrorCode();
        if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
            jdbc.update("DELETE FROM device_tokens WHERE token = :t", new MapSqlParameterSource("t", token));
            log.info("Removed stale push token ({})", code);
        } else {
            log.warn("Push failed for one token: {}", code, ex);
        }
    }
}
