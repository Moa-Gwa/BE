package com.moagwa.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.chatbot.ChatbotController.ChatRequest;
import com.moagwa.chatbot.ChatbotController.FeedbackRequest;
import com.moagwa.chatbot.ChatbotController.ResumeRequest;
import com.moagwa.common.exception.ApiException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 챗봇 서비스 호출. 챗봇 서비스에는 학생 구분이 없어, 의도 확인(clarify)에서 멈춘 대화(thread)를
 * 여기서 학생과 묶어 본인만 이어서 진행하게 함.
 */
@Slf4j
@Service
class ChatbotService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration THREAD_TTL = Duration.ofMinutes(10); // 챗봇 서비스의 확인 대기 시간과 같음
    private static final long STREAM_TIMEOUT_MS = 90_000;
    private static final String GENERIC_ERROR = "잠시 후 다시 시도해 주세요.";

    private record Owner(long userId, Instant expiresAt) {}

    private final ChatbotRepository repository;
    private final ObjectMapper json;
    private final Clock clock;
    private final RestClient http;
    private final boolean enabled;
    private final Map<String, Owner> threads = new ConcurrentHashMap<>();
    // shortcut: 대화 소유 정보는 이 서버 메모리에만 있음. BE를 여러 대로 늘리면 DB로 옮겨야 함
    // shortcut: 답변을 읽는 동안 요청마다 스레드 하나를 씀. 질문 한도가 없어 동시 사용량만큼 스레드가 늘어남
    private final ExecutorService relays = Executors.newCachedThreadPool();

    ChatbotService(ChatbotRepository repository, ObjectMapper json, Clock clock,
                   @Value("${moagwa.chatbot.url:}") String url,
                   @Value("${moagwa.chatbot.internal-token:}") String token,
                   @Value("${moagwa.chatbot.read-timeout:PT30S}") Duration readTimeout) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
        this.enabled = !url.isBlank() && !token.isBlank();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(readTimeout); // 글자 사이 대기 한도. 모델이 멈추면 학생이 끝없이 기다리지 않게 함
        this.http = RestClient.builder().baseUrl(url.isBlank() ? "http://localhost" : url).requestFactory(factory)
                .defaultHeader("X-Internal-Token", token).build();
    }

    SseEmitter ask(long userId, ChatRequest req) {
        requireEnabled();
        repository.recordQuestion(userId, LocalDate.ofInstant(clock.instant(), SEOUL)); // 한도 없이 사용량만 기록
        Map<String, Object> body = Map.of("question", req.question(), "history", Objects.requireNonNullElse(req.history(), List.of()));
        return relay(userId, "/v1/chat", body);
    }

    /** 확인 응답은 질문 수에 세지 않음 */
    SseEmitter resume(long userId, String threadId, ResumeRequest req) {
        requireEnabled();
        Owner owner = threads.get(threadId);
        // 남의 대화와 만료된 대화를 같은 응답으로 돌려 thread id 존재 여부를 알 수 없게 함
        if (owner == null || owner.userId() != userId || owner.expiresAt().isBefore(clock.instant())
                || !threads.remove(threadId, owner)) {
            throw ApiException.notFound("확인 시간이 지났어요. 다시 질문해 주세요.");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("choice", req.choice());
        body.put("index", req.index());
        return relay(userId, "/v1/chat/" + threadId + "/resume", body);
    }

    void feedback(FeedbackRequest req) {
        repository.saveFeedback(req.rating(), Objects.requireNonNullElse(req.sourceUrls(), List.of()));
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "챗봇을 아직 쓸 수 없어요.");
        }
    }

    private SseEmitter relay(long userId, String path, Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        relays.execute(() -> {
            long started = System.nanoTime();
            try {
                http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        sendError(emitter, response.getStatusCode().value() == 503
                                ? "챗봇을 준비하고 있어요. 잠시 후 다시 시도해 주세요."
                                : response.getStatusCode().value() == 404 ? "확인 시간이 지났어요. 다시 질문해 주세요." : GENERIC_ERROR);
                        return null;
                    }
                    forward(userId, emitter, response.getBody());
                    return null;
                }, true);
                emitter.complete();
            } catch (ClientGoneException e) {
                emitter.complete(); // 학생이 창을 닫음. 응답을 닫아 챗봇 서비스 요청도 끝냄
            } catch (Exception e) {
                log.warn("chatbot relay failed: {}", e.toString());
                sendError(emitter, GENERIC_ERROR);
                emitter.complete();
            } finally {
                // 질문 내용은 남기지 않고 처리 시간만 기록
                log.info("chatbot relay path={} ms={}", path.startsWith("/v1/chat/") ? "resume" : "chat",
                        (System.nanoTime() - started) / 1_000_000);
            }
        });
        return emitter;
    }

    /** 챗봇 SSE를 한 이벤트씩 그대로 넘기고, clarify의 thread id를 학생과 묶어 둠 */
    private void forward(long userId, SseEmitter emitter, InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String event = "message";
        for (String line; (line = reader.readLine()) != null; ) {
            if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                if (event.equals("clarify")) {
                    String threadId = json.readTree(data).path("thread_id").asText("");
                    if (!threadId.isEmpty()) {
                        Instant now = clock.instant();
                        threads.values().removeIf(o -> o.expiresAt().isBefore(now));
                        threads.put(threadId, new Owner(userId, now.plus(THREAD_TTL)));
                    }
                }
                send(emitter, event, data);
            } else if (line.isEmpty()) {
                event = "message";
            }
        }
    }

    private static void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data)); // 문자열 그대로(JSON 한 줄) 보냄
        } catch (IOException | IllegalStateException e) {
            throw new ClientGoneException();
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            send(emitter, "error", json.writeValueAsString(Map.of("code", "UPSTREAM", "message", message)));
        } catch (ClientGoneException | IOException ignored) {
            // 학생 연결이 이미 끊김
        }
    }

    private static class ClientGoneException extends RuntimeException {}
}
