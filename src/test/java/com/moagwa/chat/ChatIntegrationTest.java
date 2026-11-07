package com.moagwa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.auth.AuthCookies;
import com.moagwa.auth.JwtService;
import com.moagwa.notification.PushRequested;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ChatIntegrationTest {

    private static final String ORIGIN = "http://localhost:3000";

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired SimpUserRegistry connected;

    private final WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
    private String tag;

    @BeforeEach
    void setUp() {
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        stomp.stop();
    }

    @Test
    void tradePartiesChatInRealTimeAndOutsidersAreRejected() throws Exception {
        User seller = verifiedUser("seller");
        User buyer = verifiedUser("buyer");
        User outsider = verifiedUser("outsider");
        long roomId = acceptedTradeRoom(seller, buyer);

        BlockingQueue<JsonNode> sellerInbox = new LinkedBlockingQueue<>();
        BlockingQueue<JsonNode> buyerInbox = new LinkedBlockingQueue<>();
        StompSession sellerWs = connect(seller, sellerInbox, "/user/queue/chat");
        connect(buyer, buyerInbox, "/user/queue/chat");

        sellerWs.send("/app/chat.send", Map.of("roomId", roomId, "content", "  안녕하세요, 내일 가능하세요?  "));
        JsonNode received = buyerInbox.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.get("type").asText()).isEqualTo("message");
        assertThat(received.at("/message/content").asText()).isEqualTo("안녕하세요, 내일 가능하세요?");
        assertThat(received.at("/message/senderId").asLong()).isEqualTo(seller.getId());
        assertThat(sellerInbox.poll(5, TimeUnit.SECONDS)).isNotNull(); // 보낸 사람도 수신해 다른 기기·탭과 동기화
        assertThat(PushCapture.pushes).noneMatch(p -> p.url().equals("/life/chats/" + roomId)); // 둘 다 화면에서 수신
        long messageId = received.at("/message/id").asLong();

        JsonNode buyerRooms = getJson("/api/me/chats", buyer);
        assertThat(buyerRooms).hasSize(1);
        assertThat(buyerRooms.get(0).get("type").asText()).isEqualTo("TRADE");
        assertThat(buyerRooms.get(0).get("partner").asText()).isEqualTo(seller.getNickname());
        assertThat(buyerRooms.get(0).get("thumbnail").asText()).isEqualTo("/uploads/a.jpg"); // 첫 순서 사진
        assertThat(buyerRooms.get(0).get("category").asText()).isEqualTo("BOOK");
        assertThat(buyerRooms.get(0).get("unread").asLong()).isEqualTo(1);
        assertThat(getJson("/api/me/chats", seller).get(0).get("unread").asLong()).isZero();

        mvc.perform(put("/api/chats/" + roomId + "/read").header("Authorization", bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadId\":" + messageId + "}"))
                .andExpect(status().isNoContent());
        JsonNode read = sellerInbox.poll(5, TimeUnit.SECONDS);
        assertThat(read.get("type").asText()).isEqualTo("read");
        assertThat(read.at("/read/userId").asLong()).isEqualTo(buyer.getId());
        assertThat(read.at("/read/lastReadId").asLong()).isEqualTo(messageId);
        assertThat(getJson("/api/me/chats", buyer).get(0).get("unread").asLong()).isZero();

        // 같은 위치 재읽음 시 읽음 이벤트 생략
        mvc.perform(put("/api/chats/" + roomId + "/read").header("Authorization", bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadId\":" + messageId + "}"))
                .andExpect(status().isNoContent());
        assertThat(sellerInbox.poll(500, TimeUnit.MILLISECONDS)).isNull();

        JsonNode page = getJson("/api/chats/" + roomId + "/messages", buyer);
        assertThat(page.get("messages")).hasSize(1);
        assertThat(page.get("hasMore").asBoolean()).isFalse();

        mvc.perform(get("/api/chats/" + roomId + "/messages").header("Authorization", bearer(outsider)))
                .andExpect(status().isForbidden());
        assertThat(getJson("/api/me/chats", outsider)).isEmpty();
    }

    @Test
    void buyerIsToldWhenTradeRoomOpens() throws Exception {
        User seller = verifiedUser("seller");
        User buyer = verifiedUser("buyer");
        BlockingQueue<JsonNode> buyerInbox = new LinkedBlockingQueue<>();
        connect(buyer, buyerInbox, "/user/queue/chat");

        long roomId = acceptedTradeRoom(seller, buyer);

        JsonNode event = buyerInbox.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.get("type").asText()).isEqualTo("room");
        assertThat(event.get("roomId").asLong()).isEqualTo(roomId);
    }

    @Test
    void pushGoesOnlyToMembersNotListeningToChat() throws Exception {
        User seller = verifiedUser("seller");
        User buyer = verifiedUser("buyer");
        long roomId = acceptedTradeRoom(seller, buyer);
        StompSession sellerWs = connect(seller, new LinkedBlockingQueue<>(), "/user/queue/chat");
        // 연결만 있고 채팅 채널 미구독인 구매자는 푸시 대상
        connect(buyer, new LinkedBlockingQueue<>(), "/user/queue/errors");
        PushCapture.pushes.clear();

        sellerWs.send("/app/chat.send", Map.of("roomId", roomId, "content", "도착하면 연락 주세요"));

        PushRequested push = PushCapture.pushes.poll(5, TimeUnit.SECONDS);
        assertThat(push).isNotNull();
        assertThat(push.userIds()).containsExactly(buyer.getId());
        assertThat(push.url()).isEqualTo("/life/chats/" + roomId);
        assertThat(push.body()).isEqualTo("도착하면 연락 주세요");
    }

    @Test
    void outsiderCannotSendOverWebSocket() throws Exception {
        long roomId = acceptedTradeRoom(verifiedUser("seller"), verifiedUser("buyer"));
        BlockingQueue<JsonNode> errors = new LinkedBlockingQueue<>();
        StompSession ws = connect(verifiedUser("outsider"), errors, "/user/queue/errors");

        ws.send("/app/chat.send", Map.of("roomId", roomId, "content", "끼어들기"));

        assertThat(errors.poll(5, TimeUnit.SECONDS).get("message").asText()).contains("참여한 사람만");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE room_id = ?", Long.class, roomId))
                .isZero();
    }

    @Test
    void handshakeNeedsVerifiedCookieAndAllowedOrigin() {
        User plain = users.save(new User("DEV", "plain-" + tag, "plain-" + tag));
        assertThatThrownBy(() -> handshake(null, ORIGIN)).hasStackTraceContaining("401");
        assertThatThrownBy(() -> handshake(jwt.issueStudent(plain.getId()), ORIGIN)).hasStackTraceContaining("403");
        assertThatThrownBy(() -> handshake(jwt.issueStudent(verifiedUser("v").getId(), true), "https://evil.example"))
                .hasStackTraceContaining("403");
    }

    @Test
    void groupBuyRoomFollowsParticipants() throws Exception {
        User host = verifiedUser("host");
        User joiner = verifiedUser("joiner");
        Map<String, Object> body = Map.of("title", "생수 같이 사요 " + tag, "unitPrice", 3000, "targetQuantity", 5,
                "deadlineAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        long groupBuyId = json.readTree(mvc.perform(post("/api/market/group-buys").header("Authorization", bearer(host))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .at("/summary/id").asLong();

        JsonNode hostRooms = getJson("/api/me/chats", host);
        assertThat(hostRooms).hasSize(1);
        assertThat(hostRooms.get(0).get("type").asText()).isEqualTo("GROUP");
        assertThat(hostRooms.get(0).get("groupBuyId").asLong()).isEqualTo(groupBuyId);
        long roomId = hostRooms.get(0).get("id").asLong();
        assertThat(getJson("/api/me/chats", joiner)).isEmpty();

        mvc.perform(post("/api/market/group-buys/" + groupBuyId + "/join").header("Authorization", bearer(joiner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")).andExpect(status().isCreated());
        mvc.perform(get("/api/chats/" + roomId + "/messages").header("Authorization", bearer(joiner)))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/market/group-buys/" + groupBuyId + "/join").header("Authorization", bearer(joiner)))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/chats/" + roomId + "/messages").header("Authorization", bearer(joiner)))
                .andExpect(status().isForbidden());
    }

    /** 커밋 전 발행되는 푸시 요청 확인용(테스트에서 실제 FCM 발송은 꺼짐) */
    @TestConfiguration
    static class PushCapture {

        static final BlockingQueue<PushRequested> pushes = new LinkedBlockingQueue<>();

        @EventListener
        void on(PushRequested e) {
            pushes.add(e);
        }
    }

    private long acceptedTradeRoom(User seller, User buyer) throws Exception {
        Map<String, Object> item = Map.of("title", "전공책 " + tag, "price", 10000, "category", "BOOK");
        long itemId = json.readTree(mvc.perform(post("/api/market/items").header("Authorization", bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(item)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
        jdbc.update("INSERT INTO market_item_images (item_id, url, sort_order) VALUES (?, '/uploads/b.jpg', 1), (?, '/uploads/a.jpg', 0)",
                itemId, itemId);
        long requestId = json.readTree(mvc.perform(post("/api/market/items/" + itemId + "/requests")
                        .header("Authorization", bearer(buyer)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asLong();
        mvc.perform(post("/api/market/requests/" + requestId + "/accept").header("Authorization", bearer(seller)))
                .andExpect(status().isNoContent());
        return jdbc.queryForObject("SELECT id FROM chat_rooms WHERE request_id = ?", Long.class, requestId);
    }

    private StompSession connect(User user, BlockingQueue<JsonNode> inbox, String destination) throws Exception {
        StompSession session = handshake(jwt.issueStudent(user.getId(), true), ORIGIN);
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add((JsonNode) payload);
            }
        });
        // 서버에 구독 등록될 때까지 대기
        String name = String.valueOf(user.getId());
        for (int i = 0; i < 50 && !subscribed(name, destination); i++) {
            Thread.sleep(50);
        }
        return session;
    }

    private boolean subscribed(String user, String destination) {
        var u = connected.getUser(user);
        return u != null && u.getSessions().stream().flatMap(s -> s.getSubscriptions().stream())
                .anyMatch(s -> s.getDestination().equals(destination));
    }

    private StompSession handshake(String token, String origin) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        if (token != null) {
            headers.add("Cookie", AuthCookies.NAME + "=" + token);
        }
        return stomp.connectAsync("ws://localhost:" + port + "/ws", headers, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private User verifiedUser(String prefix) {
        User u = users.save(new User("DEV", prefix + "-" + tag, prefix + "-" + tag));
        jdbc.update("UPDATE users SET verified_at = now() WHERE id = ?", u.getId());
        return u;
    }

    private String bearer(User u) {
        return "Bearer " + jwt.issueStudent(u.getId(), true);
    }

    private JsonNode getJson(String path, User u) throws Exception {
        MvcResult r = mvc.perform(get(path).header("Authorization", bearer(u))).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
