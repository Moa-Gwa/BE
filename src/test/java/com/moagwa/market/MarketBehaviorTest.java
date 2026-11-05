package com.moagwa.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.auth.JwtService;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 생활거래 API의 현재 동작을 고정한다. 내부 구조를 바꿔도 이 테스트는 수정 없이 통과해야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "moagwa.verification.expose-code=true")
class MarketBehaviorTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private User seller;
    private User buyer;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        seller = verifiedUser("seller-" + tag);
        buyer = verifiedUser("buyer-" + tag);
    }

    // ---- 재학생 인증 ------------------------------------------------------------------------------------------

    @Test
    void fiveWrongCodesInvalidateTheCodeUntilANewOneIsSent() throws Exception {
        User student = unverifiedUser("wrong-" + tag);
        String code = sendCode(student, "wrong" + tag + "@catholic.ac.kr");
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            confirm(student, wrong).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("인증 코드가 올바르지 않습니다."));
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM student_verifications WHERE user_id = ?",
                Integer.class, student.getId())).isEqualTo(5);
        confirm(student, code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("코드를 5번 틀렸습니다. 새 코드를 받아 주세요."));

        allowResend(student);
        String next = sendCode(student, "wrong" + tag + "@catholic.ac.kr");
        confirm(student, next).andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    void expiredCodeIsRejected() throws Exception {
        User student = unverifiedUser("expired-" + tag);
        String code = sendCode(student, "expired" + tag + "@catholic.ac.kr");
        jdbc.update("UPDATE student_verifications SET code_expires_at = now() - interval '1 minute' WHERE user_id = ?",
                student.getId());

        confirm(student, code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("인증 코드가 만료되었습니다. 새 코드를 받아 주세요."));
    }

    @Test
    void confirmRequiresACodeAndVerifiedStudentsCannotRepeat() throws Exception {
        User student = unverifiedUser("repeat-" + tag);
        String email = "repeat" + tag + "@catholic.ac.kr";

        mvc.perform(get("/api/me/verification").header("Authorization", plainToken(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(false));
        confirm(student, "123456").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("먼저 인증 코드를 받아 주세요."));

        confirm(student, sendCode(student, email)).andExpect(status().isOk());

        allowResend(student);
        send(student, email).andExpect(status().isConflict());
        confirm(student, "123456").andExpect(status().isConflict());
    }

    // ---- 중고거래 ---------------------------------------------------------------------------------------------

    @Test
    void itemListFiltersSortsAndPages() throws Exception {
        createItem(seller, "비싼 " + tag, 3000, "BOOK", List.of());
        long cheap = createItem(seller, "싼 " + tag, 1000, "LIVING", List.of());

        items("q", tag, "sort", "price").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(cheap));
        items("q", tag, "category", "LIVING").andExpect(jsonPath("$.items.length()").value(1));
        items("q", tag, "status", "SOLD").andExpect(jsonPath("$.items.length()").value(0));
        items("q", tag, "size", "1").andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.items.length()").value(1));
        items("q", tag, "size", "1", "page", "1").andExpect(jsonPath("$.hasNext").value(false));
        items("size", "999").andExpect(jsonPath("$.size").value(50));
        items("category", "WEAPON").andExpect(status().isBadRequest());
        items("status", "LOST").andExpect(status().isBadRequest());
    }

    @Test
    void imagesKeepTheirOrderAndAreReplacedOnUpdate() throws Exception {
        long id = createItem(seller, "사진 " + tag, 1000, "BOOK", List.of("/uploads/b.png", "/uploads/a.png"));

        itemDetail(id, buyer).andExpect(jsonPath("$.imageUrls[0]").value("/uploads/b.png"))
                .andExpect(jsonPath("$.imageUrls[1]").value("/uploads/a.png"));
        items("q", tag).andExpect(jsonPath("$.items[0].thumbnail").value("/uploads/b.png"));

        Map<String, Object> body = itemBody("수정 " + tag, 2000, "ELECTRONICS", List.of("/uploads/c.png"));
        sendJson(put("/api/market/items/" + id), seller, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("수정 " + tag)).andExpect(jsonPath("$.price").value(2000))
                .andExpect(jsonPath("$.mine").value(true)).andExpect(jsonPath("$.imageUrls.length()").value(1))
                .andExpect(jsonPath("$.imageUrls[0]").value("/uploads/c.png"));
    }

    @Test
    void onlyOtherUsersIncreaseTheViewCount() throws Exception {
        long id = createItem(seller, "조회 " + tag, 1000, "BOOK", List.of());

        itemDetail(id, seller).andExpect(jsonPath("$.viewCount").value(0));
        itemDetail(id, buyer).andExpect(jsonPath("$.viewCount").value(1));
    }

    @Test
    void likesAreIdempotent() throws Exception {
        long id = createItem(seller, "관심 " + tag, 1000, "BOOK", List.of());
        String likes = "/api/market/items/" + id + "/likes";

        mvc.perform(put(likes).header("Authorization", token(buyer))).andExpect(status().isNoContent());
        mvc.perform(put(likes).header("Authorization", token(buyer))).andExpect(status().isNoContent());
        itemDetail(id, buyer).andExpect(jsonPath("$.likeCount").value(1));

        mvc.perform(delete(likes).header("Authorization", token(buyer))).andExpect(status().isNoContent());
        mvc.perform(delete(likes).header("Authorization", token(buyer))).andExpect(status().isNoContent());
        itemDetail(id, buyer).andExpect(jsonPath("$.likeCount").value(0)).andExpect(jsonPath("$.liked").value(false));

        mvc.perform(put("/api/market/items/0/likes").header("Authorization", token(buyer)))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusChangeRejectsUnknownValuesAndOtherUsers() throws Exception {
        long id = createItem(seller, "상태 " + tag, 1000, "BOOK", List.of());
        String path = "/api/market/items/" + id + "/status";

        sendJson(post(path), seller, Map.of("status", "LOST")).andExpect(status().isBadRequest());
        sendJson(post(path), buyer, Map.of("status", "SOLD")).andExpect(status().isForbidden());
        sendJson(post("/api/market/items/0/status"), seller, Map.of("status", "SOLD")).andExpect(status().isNotFound());
    }

    // ---- 거래 요청 --------------------------------------------------------------------------------------------

    @Test
    void acceptingARequestReservesTheItemAndRejectsOtherPendingRequests() throws Exception {
        User other = verifiedUser("other-" + tag);
        long id = createItem(seller, "요청 " + tag, 1000, "BOOK", List.of());
        long accepted = requestItem(id, buyer, "먼저 요청");
        long rejected = requestItem(id, other, "늦은 요청");

        mvc.perform(get("/api/market/items/" + id + "/requests").header("Authorization", token(seller)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(rejected))
                .andExpect(jsonPath("$[0].buyerNickname").value("other-" + tag))
                .andExpect(jsonPath("$[0].message").value("늦은 요청"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        accept(accepted, buyer).andExpect(status().isForbidden());
        accept(accepted, seller).andExpect(status().isNoContent());

        itemDetail(id, buyer).andExpect(jsonPath("$.status").value("RESERVED"));
        mvc.perform(get("/api/me/market/requests").header("Authorization", token(other)))
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[0].itemTitle").value("요청 " + tag));
        accept(accepted, seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 처리된 요청입니다."));
        accept(rejected, seller).andExpect(status().isConflict());
        accept(0, seller).andExpect(status().isNotFound());
    }

    @Test
    void requestsNeedAnItemOnSale() throws Exception {
        long id = createItem(seller, "판매완료 " + tag, 1000, "BOOK", List.of());
        sendJson(post("/api/market/items/" + id + "/status"), seller, Map.of("status", "SOLD"))
                .andExpect(status().isOk());

        sendJson(post("/api/market/items/" + id + "/requests"), buyer, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("판매 중인 물품에만 거래를 요청할 수 있습니다."));
        sendJson(post("/api/market/items/0/requests"), buyer, Map.of()).andExpect(status().isNotFound());
    }

    @Test
    void acceptNeedsTheItemToStillBeOnSale() throws Exception {
        long id = createItem(seller, "수락불가 " + tag, 1000, "BOOK", List.of());
        long requestId = requestItem(id, buyer, "요청");
        sendJson(post("/api/market/items/" + id + "/status"), seller, Map.of("status", "SOLD"))
                .andExpect(status().isOk());

        accept(requestId, seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("판매 중인 물품만 수락할 수 있습니다."));
    }

    // ---- 내 거래 ----------------------------------------------------------------------------------------------

    @Test
    void myItemsListsOnlyMyOwnItems() throws Exception {
        long mine = createItem(seller, "내 물품 " + tag, 1000, "BOOK", List.of());
        createItem(buyer, "남의 물품 " + tag, 1000, "BOOK", List.of());

        mvc.perform(get("/api/me/market/items").header("Authorization", token(seller)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(mine));
    }

    @Test
    void myGroupBuysIncludeHostedAndJoinedOnes() throws Exception {
        long hosted = createGroupBuy(seller, "연 모집 " + tag, 5);
        long joined = createGroupBuy(buyer, "참여 모집 " + tag, 5);
        createGroupBuy(buyer, "무관한 모집 " + tag, 5);
        join(joined, seller, 1).andExpect(status().isCreated());

        JsonNode rows = readBody(mvc.perform(get("/api/me/market/group-buys").header("Authorization", token(seller)))
                .andExpect(status().isOk()));
        assertThat(rows.findValues("id").stream().map(JsonNode::asLong).toList())
                .containsExactlyInAnyOrder(hosted, joined);
    }

    // ---- 같이 사기 --------------------------------------------------------------------------------------------

    @Test
    void joiningAgainChangesTheQuantityAndLeavingFreesIt() throws Exception {
        long id = createGroupBuy(seller, "수량 " + tag, 5);

        join(id, buyer, 4).andExpect(status().isCreated()).andExpect(jsonPath("$.participants").value(1))
                .andExpect(jsonPath("$.mine").value(false));
        join(id, buyer, 5).andExpect(status().isCreated()).andExpect(jsonPath("$.summary.joinedQuantity").value(5))
                .andExpect(jsonPath("$.summary.myQuantity").value(5)).andExpect(jsonPath("$.participants").value(1));
        join(id, seller, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("남은 수량이 부족합니다."));

        mvc.perform(delete("/api/market/group-buys/" + id + "/join").header("Authorization", token(buyer)))
                .andExpect(status().isNoContent());
        join(id, seller, 5).andExpect(status().isCreated()).andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.summary.joinedQuantity").value(5));
    }

    @Test
    void joinLimitsTheQuantityPerPerson() throws Exception {
        long id = createGroupBuy(seller, "한도 " + tag, 100);

        join(id, buyer, 0).andExpect(status().isBadRequest());
        join(id, buyer, 21).andExpect(status().isBadRequest());
    }

    @Test
    void passedDeadlineCountsAsClosedEvenIfStoredAsRecruiting() throws Exception {
        String title = "지난 모집 " + tag;
        Long id = jdbc.queryForObject("""
                INSERT INTO group_buys (host_id, title, description, unit_price, target_quantity, deadline_at)
                VALUES (?, ?, '', 1000, 3, now() - interval '1 hour') RETURNING id""",
                Long.class, seller.getId(), title);

        groupBuy(id, buyer).andExpect(status().isOk()).andExpect(jsonPath("$.summary.status").value("CLOSED"))
                .andExpect(jsonPath("$.summary.hostNickname").value("seller-" + tag));
        join(id, buyer, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 마감된 모집입니다."));
        mvc.perform(delete("/api/market/group-buys/" + id + "/join").header("Authorization", token(buyer)))
                .andExpect(status().isConflict());
        groupBuys("q", tag).andExpect(jsonPath("$.items.length()").value(0));
        groupBuys("q", tag, "status", "CLOSED").andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void groupBuyRejectsUnknownStatusAndMissingTargets() throws Exception {
        groupBuys("status", "LOST").andExpect(status().isBadRequest());
        groupBuy(0, buyer).andExpect(status().isNotFound());
        join(0, buyer, 1).andExpect(status().isNotFound());
        mvc.perform(post("/api/market/group-buys/0/close").header("Authorization", token(seller)))
                .andExpect(status().isNotFound());
    }

    @Test
    void groupBuyImageMustBeAnUploadedFile() throws Exception {
        Map<String, Object> body = new HashMap<>(groupBuyBody("사진 모집 " + tag, 3));
        body.put("imageUrl", "https://evil.example/a.png");

        sendJson(post("/api/market/group-buys"), seller, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("업로드한 이미지만 첨부할 수 있습니다."));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private User verifiedUser(String name) {
        User user = unverifiedUser(name);
        jdbc.update("UPDATE users SET verified_at = now() WHERE id = ?", user.getId());
        return user;
    }

    private User unverifiedUser(String name) {
        return users.save(new User("DEV", name, name));
    }

    private String token(User user) {
        return "Bearer " + jwt.issueStudent(user.getId(), true);
    }

    private String plainToken(User user) {
        return "Bearer " + jwt.issueStudent(user.getId());
    }

    private JsonNode readBody(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions sendJson(MockHttpServletRequestBuilder request, User by, Map<String, ?> body)
            throws Exception {
        return mvc.perform(request.header("Authorization", token(by)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private ResultActions send(User student, String email) throws Exception {
        return mvc.perform(post("/api/me/verification/email").header("Authorization", plainToken(student))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email))));
    }

    private String sendCode(User student, String email) throws Exception {
        return readBody(send(student, email).andExpect(status().isOk())).get("devCode").asText();
    }

    private ResultActions confirm(User student, String code) throws Exception {
        return mvc.perform(post("/api/me/verification/confirm").header("Authorization", plainToken(student))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code", code))));
    }

    private void allowResend(User student) {
        jdbc.update("UPDATE student_verifications SET last_sent_at = now() - interval '2 minutes' WHERE user_id = ?",
                student.getId());
    }

    private Map<String, Object> itemBody(String title, int price, String category, List<String> imageUrls) {
        return Map.of("title", title, "price", price, "category", category, "imageUrls", imageUrls);
    }

    private long createItem(User by, String title, int price, String category, List<String> imageUrls) throws Exception {
        return readBody(sendJson(post("/api/market/items"), by, itemBody(title, price, category, imageUrls))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private ResultActions items(String... params) throws Exception {
        return list("/api/market/items", params);
    }

    private ResultActions groupBuys(String... params) throws Exception {
        return list("/api/market/group-buys", params);
    }

    private ResultActions list(String path, String... params) throws Exception {
        var request = get(path).header("Authorization", token(buyer));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions itemDetail(long id, User by) throws Exception {
        return mvc.perform(get("/api/market/items/" + id).header("Authorization", token(by)));
    }

    private long requestItem(long itemId, User by, String message) throws Exception {
        return readBody(sendJson(post("/api/market/items/" + itemId + "/requests"), by, Map.of("message", message))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))).get("id").asLong();
    }

    private ResultActions accept(long requestId, User by) throws Exception {
        return mvc.perform(post("/api/market/requests/" + requestId + "/accept").header("Authorization", token(by)));
    }

    private Map<String, Object> groupBuyBody(String title, int target) {
        return Map.of("title", title, "unitPrice", 1000, "targetQuantity", target,
                "deadlineAt", Instant.now().plus(2, ChronoUnit.DAYS).toString());
    }

    private long createGroupBuy(User by, String title, int target) throws Exception {
        return readBody(sendJson(post("/api/market/group-buys"), by, groupBuyBody(title, target))
                .andExpect(status().isCreated())).get("summary").get("id").asLong();
    }

    private ResultActions groupBuy(long id, User by) throws Exception {
        return mvc.perform(get("/api/market/group-buys/" + id).header("Authorization", token(by)));
    }

    private ResultActions join(long id, User by, int quantity) throws Exception {
        return sendJson(post("/api/market/group-buys/" + id + "/join"), by, Map.of("quantity", quantity));
    }
}
