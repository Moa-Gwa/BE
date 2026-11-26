package com.moagwa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 카카오 인증·API 서버를 로컬 가짜 서버로 바꿔 POST /api/auth/kakao 전체 흐름을 검증함 */
@SpringBootTest
@AutoConfigureMockMvc
class KakaoLoginTest {

    private static final String REDIRECT_URI = "https://localhost:3000/login/kakao";

    /** 가짜 카카오 서버가 받은 요청 */
    record Received(String method, String path, String contentType, String authorization, String body) {

        Map<String, String> form() {
            return Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2)).collect(Collectors.toMap(
                    kv -> decode(kv[0]), kv -> kv.length > 1 ? decode(kv[1]) : ""));
        }

        private static String decode(String s) {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        }
    }

    private static final HttpServer KAKAO = start();
    private static final List<Received> received = new CopyOnWriteArrayList<>();
    private static volatile int tokenStatus;
    private static volatile String tokenBody;
    private static volatile int profileStatus;
    private static volatile String profileBody;

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/oauth/token", exchange -> reply(exchange, tokenStatus, tokenBody));
            server.createContext("/v2/user/me", exchange -> reply(exchange, profileStatus, profileBody));
            server.createContext("/v1/user/unlink", exchange -> reply(exchange, 200, "{}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void kakao(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + KAKAO.getAddress().getPort();
        registry.add("moagwa.kakao.auth-url", () -> url);
        registry.add("moagwa.kakao.api-url", () -> url);
        registry.add("moagwa.kakao.client-id", () -> "test-client-id");
        registry.add("moagwa.kakao.client-secret", () -> "test-client-secret");
        registry.add("moagwa.kakao.admin-key", () -> "test-admin-key");
    }

    @AfterAll
    static void stop() {
        KAKAO.stop(0);
    }

    private String accessToken;
    private long kakaoId;

    @BeforeEach
    void setUp() {
        received.clear();
        accessToken = "access-" + UUID.randomUUID();
        kakaoId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        tokenStatus = 200;
        tokenBody = "{\"access_token\":\"" + accessToken + "\",\"token_type\":\"bearer\"}";
        profileStatus = 200;
        profileBody = "{\"id\":" + kakaoId + ",\"properties\":{\"nickname\":\"카카오학생\"}}";
    }

    private ResultActions login(String code) throws Exception {
        return mvc.perform(post("/api/auth/kakao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"redirectUri\":\"" + REDIRECT_URI + "\"}"));
    }

    private Optional<User> kakaoUser() {
        return users.findByAuthProviderAndProviderUserId("KAKAO", String.valueOf(kakaoId));
    }

    @Test
    void validCodeIsExchangedAndCreatesTheStudentOnce() throws Exception {
        login("code-1").andExpect(status().isOk())
                .andExpect(cookie().httpOnly(AuthCookies.NAME, true))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.nickname").value("카카오학생"))
                .andExpect(jsonPath("$.onboarded").value(false))
                .andExpect(jsonPath("$.verified").value(false));

        assertThat(received).hasSize(2);
        Received token = received.get(0);
        assertThat(token.method()).isEqualTo("POST");
        assertThat(token.path()).isEqualTo("/oauth/token");
        assertThat(token.contentType()).startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        assertThat(token.form()).containsEntry("grant_type", "authorization_code")
                .containsEntry("client_id", "test-client-id")
                .containsEntry("client_secret", "test-client-secret")
                .containsEntry("redirect_uri", REDIRECT_URI)
                .containsEntry("code", "code-1");
        Received profile = received.get(1);
        assertThat(profile.method()).isEqualTo("GET");
        assertThat(profile.path()).isEqualTo("/v2/user/me");
        assertThat(profile.authorization()).isEqualTo("Bearer " + accessToken);

        long id = kakaoUser().orElseThrow().getId();
        login("code-2").andExpect(status().isOk());
        assertThat(kakaoUser()).get().extracting(User::getId).isEqualTo(id);
    }

    @Test
    void missingNicknameFallsBackToDefault() throws Exception {
        profileBody = "{\"id\":" + kakaoId + "}";

        login("code").andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("학생"));
    }

    @Test
    void rejectedCodeReturns401WithoutCallingTheProfileApi() throws Exception {
        tokenStatus = 400;
        tokenBody = "{\"error\":\"invalid_grant\",\"error_code\":\"KOE320\"}";

        login("expired").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("카카오 인증에 실패했습니다."))
                .andExpect(cookie().doesNotExist(AuthCookies.NAME));
        assertThat(received).extracting(Received::path).containsExactly("/oauth/token");
        assertThat(kakaoUser()).isEmpty();
    }

    @Test
    void profileFailureReturns401AndCreatesNoStudent() throws Exception {
        profileStatus = 401;
        profileBody = "{\"msg\":\"this access token does not exist\",\"code\":-401}";

        login("code").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("카카오 인증에 실패했습니다."));
        assertThat(kakaoUser()).isEmpty();
    }

    @Test
    void blankCodeIsRejectedBeforeCallingKakao() throws Exception {
        login(" ").andExpect(status().isBadRequest());
        assertThat(received).isEmpty();
    }

    @Test
    void withdrawalUnlinksTheKakaoAccountWithTheAdminKey() throws Exception {
        login("code").andExpect(status().isOk());
        received.clear();

        mvc.perform(delete("/api/me").header("Authorization", "Bearer " + jwt.issueStudent(kakaoUser().orElseThrow(), false)))
                .andExpect(status().isNoContent());

        assertThat(kakaoUser()).isEmpty();
        assertThat(received).hasSize(1);
        Received unlink = received.get(0);
        assertThat(unlink.path()).isEqualTo("/v1/user/unlink");
        assertThat(unlink.authorization()).isEqualTo("KakaoAK test-admin-key");
        assertThat(unlink.form()).containsEntry("target_id_type", "user_id")
                .containsEntry("target_id", String.valueOf(kakaoId));
    }
}
