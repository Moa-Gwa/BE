package com.moagwa.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moagwa.auth.JwtService;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 챗봇 서비스를 로컬 가짜 서버로 바꿔 중계, 대화 소유 확인, 질문 한도 없음을 검증함 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatbotRelayTest {

    record Received(String path, String token, String body) {}

    private static final HttpServer CHATBOT = start();
    private static final List<Received> received = new CopyOnWriteArrayList<>();
    private static volatile int status = 200;
    private static volatile String reply = "";

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private User me;
    private User other;

    @DynamicPropertySource
    static void chatbot(DynamicPropertyRegistry registry) {
        registry.add("moagwa.chatbot.url", () -> "http://127.0.0.1:" + CHATBOT.getAddress().getPort());
        registry.add("moagwa.chatbot.internal-token", () -> "test-internal-token");
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat", ChatbotRelayTest::reply);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange exchange) throws IOException {
        received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String sse(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        me = users.save(new User("DEV", "bot-me-" + tag, "me"));
        other = users.save(new User("DEV", "bot-other-" + tag, "other"));
        received.clear();
        status = 200;
        reply = sse("sources", "[{\"n\":1,\"title\":\"휴학\"}]") + sse("token", "{\"text\":\"학사행정팀에 문의해요 [1]\"}")
                + sse("done", "{\"grounded\":true,\"cited\":[1]}");
    }

    private ResultActions call(String path, User user, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + jwt.issueStudent(user.getId()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** SSE 응답이 끝날 때까지 기다린 뒤 본문 */
    private String stream(String path, User user, String body) throws Exception {
        MvcResult result = call(path, user, body).andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10_000);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void relaysChatbotEventsAsTheyAre() throws Exception {
        String body = stream("/api/me/chatbot", me,
                "{\"question\":\"휴학 신청\",\"history\":[{\"role\":\"user\",\"content\":\"안녕\"}]}");

        assertThat(body).contains("event:sources", "event:token", "data:{\"text\":\"학사행정팀에 문의해요 [1]\"}",
                "event:done", "\"cited\":[1]");
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/v1/chat");
            assertThat(r.token()).isEqualTo("test-internal-token");
            assertThat(r.body()).contains("\"question\":\"휴학 신청\"", "\"role\":\"user\"");
        });
    }

    @Test
    void onlyTheStudentWhoGotTheClarifyCanResumeIt() throws Exception {
        String thread = "t_" + UUID.randomUUID().toString().replace("-", "");
        reply = sse("clarify", "{\"thread_id\":\"" + thread + "\",\"interpretation\":\"일반휴학\",\"alternatives\":[\"군휴학\"]}");
        assertThat(stream("/api/me/chatbot", me, "{\"question\":\"휴학\"}")).contains("event:clarify");

        call("/api/me/chatbot/" + thread + "/resume", other, "{\"choice\":\"confirm\"}").andExpect(status().isNotFound());

        reply = sse("token", "{\"text\":\"일반휴학은\"}") + sse("done", "{\"grounded\":true}");
        assertThat(stream("/api/me/chatbot/" + thread + "/resume", me, "{\"choice\":\"alternative\",\"index\":0}"))
                .contains("event:token");
        assertThat(received.get(received.size() - 1).path()).isEqualTo("/v1/chat/" + thread + "/resume");
        assertThat(received.get(received.size() - 1).body()).contains("\"choice\":\"alternative\"", "\"index\":0");

        // 한 번 이어서 진행한 대화는 다시 쓸 수 없음
        call("/api/me/chatbot/" + thread + "/resume", me, "{\"choice\":\"confirm\"}").andExpect(status().isNotFound());
    }

    @Test
    void hasNoQuestionLimitButRecordsUsage() throws Exception {
        // 예전 하루 한도(30회)를 이미 넘긴 학생도, 1분 안에 연달아 묻는 질문도 모두 챗봇으로 넘어감
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        jdbc.update("INSERT INTO chatbot_usage (user_id, day, count) VALUES (?, ?, 30)", other.getId(), today);
        for (int i = 0; i < 6; i++) {
            assertThat(stream("/api/me/chatbot", me, "{\"question\":\"질문" + i + "\"}")).contains("event:done");
        }
        assertThat(stream("/api/me/chatbot", other, "{\"question\":\"한도 넘긴 학생\"}")).contains("event:done");

        assertThat(received).hasSize(7);
        assertThat(jdbc.queryForObject("SELECT count FROM chatbot_usage WHERE user_id = ? AND day = ?", Integer.class,
                me.getId(), today)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count FROM chatbot_usage WHERE user_id = ? AND day = ?", Integer.class,
                other.getId(), today)).isEqualTo(31);
    }

    @Test
    void chatbotFailureBecomesAnErrorEvent() throws Exception {
        status = 503;
        reply = "{\"detail\":\"indexing\"}";

        assertThat(stream("/api/me/chatbot", me, "{\"question\":\"휴학\"}"))
                .contains("event:error", "챗봇을 준비하고 있어요. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    void rejectsAnonymousAndInvalidRequests() throws Exception {
        mvc.perform(post("/api/me/chatbot").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"휴학\"}"))
                .andExpect(status().isUnauthorized());
        call("/api/me/chatbot", me, "{\"question\":\"" + "가".repeat(501) + "\"}").andExpect(status().isBadRequest());
        call("/api/me/chatbot", me, "{\"question\":\"휴학\",\"history\":[{\"role\":\"system\",\"content\":\"x\"}]}")
                .andExpect(status().isBadRequest());
        assertThat(received).isEmpty();
    }

    @Test
    void savesFeedbackWithoutTheQuestion() throws Exception {
        String url = "https://www.catholic.ac.kr/" + UUID.randomUUID();
        call("/api/me/chatbot/feedback", me, "{\"rating\":\"not_helpful\",\"sourceUrls\":[\"" + url + "\"]}")
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT rating FROM chatbot_feedback WHERE source_urls = ?", String.class, url))
                .isEqualTo("not_helpful");
    }
}
