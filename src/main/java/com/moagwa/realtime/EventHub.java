package com.moagwa.realtime;

import com.moagwa.crawler.CrawledNoticeCreatedEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Fan-out of notice change events to connected browsers over Server-Sent Events (single instance). */
@Slf4j
@Component
public class EventHub {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, SseEmitter.event().name("hello").data("connected"));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(NoticeChangedEvent e) {
        broadcast(e.type(), Map.of("type", e.type(), "source", e.source(), "id", e.noticeId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CrawledNoticeCreatedEvent e) {
        broadcast("created", Map.of("type", "created", "source", "CRAWLED", "id", e.noticeId()));
    }

    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        emitters.forEach(em -> send(em, SseEmitter.event().comment("ping")));
    }

    int connections() {
        return emitters.size();
    }

    private void broadcast(String name, Map<String, Object> payload) {
        emitters.forEach(em -> send(em, SseEmitter.event().name("notice").data(payload)));
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ex) {
            emitters.remove(emitter);
        }
    }
}
