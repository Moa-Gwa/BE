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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "moagwa.verification.expose-code=true")
class MarketApiIntegrationTest {

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

    private User verifiedUser(String name) {
        User u = users.save(new User("DEV", name, name));
        jdbc.update("UPDATE users SET verified_at = now() WHERE id = ?", u.getId());
        return u;
    }

    private String token(User u) {
        return "Bearer " + jwt.issueStudent(u.getId(), true);
    }

    private JsonNode read(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long createItem(User by, String title) throws Exception {
        Map<String, Object> body = Map.of("title", title, "description", "상태 좋아요", "price", 12000,
                "category", "BOOK", "tradePlace", "중앙도서관 앞");
        MvcResult r = mvc.perform(post("/api/market/items").header("Authorization", token(by))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return read(r).get("id").asLong();
    }

    @Test
    void marketRequiresVerifiedStudent() throws Exception {
        User plain = users.save(new User("DEV", "plain-" + tag, "plain-" + tag));
        mvc.perform(get("/api/market/items")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/market/items").header("Authorization", "Bearer " + jwt.issueStudent(plain.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/market/items").header("Authorization", token(plain))).andExpect(status().isOk());
    }

    @Test
    void emailVerificationIssuesVerifiedToken() throws Exception {
        User plain = users.save(new User("DEV", "plain2-" + tag, "plain2-" + tag));
        String auth = "Bearer " + jwt.issueStudent(plain.getId());
        String email = "student" + tag + "@catholic.ac.kr";

        mvc.perform(post("/api/me/verification/email").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@gmail.com\"}"))
                .andExpect(status().isBadRequest());
        MvcResult sent = mvc.perform(post("/api/me/verification/email").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.devCode").isNotEmpty()).andReturn();
        String code = read(sent).get("devCode").asText();

        mvc.perform(post("/api/me/verification/email").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isTooManyRequests());
        String wrong = code.equals("000000") ? "111111" : "000000";
        mvc.perform(post("/api/me/verification/confirm").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + wrong + "\"}"))
                .andExpect(status().isBadRequest());
        MvcResult done = mvc.perform(post("/api/me/verification/confirm").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true)).andReturn();

        String verifiedToken = "Bearer " + read(done).get("token").asText();
        mvc.perform(get("/api/market/items").header("Authorization", verifiedToken)).andExpect(status().isOk());
        mvc.perform(get("/api/me/verification").header("Authorization", verifiedToken))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.schoolEmail").value("stu***@catholic.ac.kr"));
    }

    @Test
    void schoolEmailBelongsToOneAccount() throws Exception {
        User first = users.save(new User("DEV", "first-" + tag, "first-" + tag));
        User second = users.save(new User("DEV", "second-" + tag, "second-" + tag));
        String email = "same" + tag + "@catholic.ac.kr";
        String a = "Bearer " + jwt.issueStudent(first.getId());
        String b = "Bearer " + jwt.issueStudent(second.getId());

        MvcResult sent = mvc.perform(post("/api/me/verification/email").header("Authorization", a)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/me/verification/confirm").header("Authorization", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + read(sent).get("devCode").asText() + "\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/me/verification/email").header("Authorization", b)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void itemLifecycleWithLikesAndTradeRequest() throws Exception {
        long id = createItem(seller, "알고리즘 책 " + tag);

        mvc.perform(get("/api/market/items").param("q", tag).header("Authorization", token(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("ON_SALE"));
        mvc.perform(put("/api/market/items/" + id + "/likes").header("Authorization", token(buyer)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/market/items/" + id).header("Authorization", token(buyer)))
                .andExpect(jsonPath("$.likeCount").value(1)).andExpect(jsonPath("$.liked").value(true))
                .andExpect(jsonPath("$.mine").value(false)).andExpect(jsonPath("$.seller.nickname").value("seller-" + tag));
        mvc.perform(get("/api/me/market/likes").header("Authorization", token(buyer)))
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(post("/api/market/items/" + id + "/requests").header("Authorization", token(seller))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        MvcResult req = mvc.perform(post("/api/market/items/" + id + "/requests").header("Authorization", token(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"내일 가능해요\"}"))
                .andExpect(status().isCreated()).andReturn();
        mvc.perform(post("/api/market/items/" + id + "/requests").header("Authorization", token(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
        mvc.perform(get("/api/market/items/" + id + "/requests").header("Authorization", token(buyer)))
                .andExpect(status().isForbidden());

        long requestId = read(req).get("id").asLong();
        mvc.perform(post("/api/market/requests/" + requestId + "/accept").header("Authorization", token(seller)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/market/items/" + id).header("Authorization", token(buyer)))
                .andExpect(jsonPath("$.status").value("RESERVED"));
        mvc.perform(get("/api/me/market/requests").header("Authorization", token(buyer)))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"));
    }

    @Test
    void onlySellerCanChangeOrDeleteItem() throws Exception {
        long id = createItem(seller, "남의 물품 " + tag);
        Map<String, Object> body = Map.of("title", "바꿈", "price", 1, "category", "BOOK");

        mvc.perform(put("/api/market/items/" + id).header("Authorization", token(buyer))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/market/items/" + id).header("Authorization", token(buyer)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/market/items/" + id + "/status").header("Authorization", token(seller))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SOLD\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SOLD"));
        mvc.perform(delete("/api/market/items/" + id).header("Authorization", token(seller)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/market/items/" + id).header("Authorization", token(seller)))
                .andExpect(status().isNotFound());
    }

    @Test
    void itemRejectsBadInput() throws Exception {
        Map<String, Object> badCategory = Map.of("title", "x", "price", 1, "category", "WEAPON");
        mvc.perform(post("/api/market/items").header("Authorization", token(seller))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(badCategory)))
                .andExpect(status().isBadRequest());
        Map<String, Object> badImage = Map.of("title", "x", "price", 1, "category", "BOOK",
                "imageUrls", List.of("https://evil.example/a.png"));
        mvc.perform(post("/api/market/items").header("Authorization", token(seller))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(badImage)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void groupBuyJoinRespectsTargetQuantityAndDeadline() throws Exception {
        Map<String, Object> body = Map.of("title", "잉크 같이 사요 " + tag, "unitPrice", 9000, "targetQuantity", 3,
                "deadlineAt", Instant.now().plus(2, ChronoUnit.DAYS).toString(), "pickupPlace", "도서관 1층");
        MvcResult created = mvc.perform(post("/api/market/group-buys").header("Authorization", token(seller))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.summary.status").value("RECRUITING")).andReturn();
        long id = read(created).get("summary").get("id").asLong();

        mvc.perform(post("/api/market/group-buys/" + id + "/join").header("Authorization", token(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.summary.joinedQuantity").value(2))
                .andExpect(jsonPath("$.summary.myQuantity").value(2));
        mvc.perform(post("/api/market/group-buys/" + id + "/join").header("Authorization", token(seller))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}")).andExpect(status().isConflict());
        mvc.perform(post("/api/market/group-buys/" + id + "/join").header("Authorization", token(seller))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")).andExpect(status().isCreated());
        mvc.perform(get("/api/me/market/group-buys").header("Authorization", token(buyer)))
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(post("/api/market/group-buys/" + id + "/close").header("Authorization", token(buyer)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/market/group-buys/" + id + "/close").header("Authorization", token(seller)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/market/group-buys/" + id + "/join").header("Authorization", token(buyer)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/market/group-buys/" + id).header("Authorization", token(buyer)))
                .andExpect(jsonPath("$.summary.status").value("CLOSED"));
    }

    @Test
    void groupBuyRejectsPastDeadline() throws Exception {
        Map<String, Object> body = Map.of("title", "지난 모집", "unitPrice", 1000, "targetQuantity", 2,
                "deadlineAt", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        mvc.perform(post("/api/market/group-buys").header("Authorization", token(seller))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(tag).isNotBlank();
    }
}
