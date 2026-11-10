package com.moagwa.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.Map;
import java.util.UUID;
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

/** 같이 사기 결제: 토스 API는 가짜로 대체하고 승인·환불 호출 여부까지 검증 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupBuyPaymentTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired GroupBuyPaymentService paymentService;
    @MockBean TossPaymentsClient toss;

    private User host;
    private User buyer;
    private User other;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        host = verifiedUser("host-" + tag);
        buyer = verifiedUser("buyer-" + tag);
        other = verifiedUser("other-" + tag);
        when(toss.enabled()).thenReturn(true);
        when(toss.confirm(anyString(), anyString(), anyLong())).thenAnswer(inv -> new TossPaymentsClient.Payment(
                inv.getArgument(0), inv.getArgument(1), "DONE", inv.getArgument(2)));
    }

    @Test
    void paidJoinConfirmsOnceAndRefundsOnLeave() throws Exception {
        long id = createGroupBuy(3000, 3);
        perform(post("/api/market/group-buys/" + id + "/join"), buyer, "{\"quantity\":1}")
                .andExpect(status().isConflict());

        JsonNode order = order(id, buyer, 2);
        assertThat(order.get("amount").asInt()).isEqualTo(6000);
        String orderId = order.get("orderId").asText();

        mvc.perform(get("/api/market/group-buys/" + id).header("Authorization", token(buyer)))
                .andExpect(jsonPath("$.myPaid").value(false));
        confirm(buyer, "pk-" + orderId, orderId, 6000).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.myQuantity").value(2)).andExpect(jsonPath("$.myPaid").value(true));
        // 성공 페이지 새로고침 등 같은 승인 재요청은 결과만 반환
        confirm(buyer, "pk-" + orderId, orderId, 6000).andExpect(status().isOk());
        verify(toss, times(1)).confirm(anyString(), eq(orderId), anyLong());

        perform(post("/api/market/group-buys/" + id + "/payments"), buyer, "{\"quantity\":1}")
                .andExpect(status().isConflict());
        mvc.perform(get("/api/market/group-buys/" + id + "/participants").header("Authorization", token(host)))
                .andExpect(jsonPath("$[0].paid").value(true)).andExpect(jsonPath("$[0].quantity").value(2));
        mvc.perform(get("/api/market/group-buys/" + id + "/participants").header("Authorization", token(buyer)))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/market/group-buys/" + id + "/join").header("Authorization", token(buyer)))
                .andExpect(status().isNoContent());
        verify(toss).cancel("pk-" + orderId, orderId, "참여 취소");
        assertThat(paymentStatus(orderId)).isEqualTo("CANCELED");
        assertThat(participantCount(id)).isZero();
    }

    @Test
    void confirmRejectsWrongAmountAndFullGroupWithoutCharging() throws Exception {
        long id = createGroupBuy(1000, 2);
        String mine = order(id, buyer, 2).get("orderId").asText();
        String others = order(id, other, 1).get("orderId").asText();

        confirm(buyer, "pk-" + mine, mine, 1).andExpect(status().isBadRequest());
        confirm(buyer, "pk-" + mine, mine, 2000).andExpect(status().isOk());
        // 다른 사람이 먼저 남은 수량을 채우면 승인 전에 막혀 결제되지 않음
        confirm(other, "pk-" + others, others, 1000).andExpect(status().isConflict());
        confirm(other, "pk-" + mine, mine, 2000).andExpect(status().isForbidden());

        verify(toss, never()).confirm(anyString(), eq(others), anyLong());
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void hostCancelAndMissedTargetRefundEveryPayment() throws Exception {
        long canceledByHost = createGroupBuy(2000, 5);
        String a = paidOrder(canceledByHost, buyer, 1);
        String b = paidOrder(canceledByHost, other, 2);
        perform(post("/api/market/group-buys/" + canceledByHost + "/cancel"), buyer, null)
                .andExpect(status().isForbidden());
        perform(post("/api/market/group-buys/" + canceledByHost + "/cancel"), host, null)
                .andExpect(status().isNoContent());
        assertThat(paymentStatus(a)).isEqualTo("CANCELED");
        assertThat(paymentStatus(b)).isEqualTo("CANCELED");
        mvc.perform(get("/api/market/group-buys/" + canceledByHost).header("Authorization", token(buyer)))
                .andExpect(jsonPath("$.summary.status").value("CANCELED"));

        long missed = createGroupBuy(2000, 5);
        String c = paidOrder(missed, buyer, 1);
        jdbc.update("UPDATE group_buys SET deadline_at = now() - interval '1 minute' WHERE id = ?", missed);
        paymentService.refundFailedGroupBuys();
        assertThat(paymentStatus(c)).isEqualTo("CANCELED");
        verify(toss).cancel("pk-" + c, c, "모집 취소");
    }

    private String paidOrder(long id, User user, int quantity) throws Exception {
        JsonNode order = order(id, user, quantity);
        String orderId = order.get("orderId").asText();
        confirm(user, "pk-" + orderId, orderId, order.get("amount").asLong()).andExpect(status().isOk());
        return orderId;
    }

    private JsonNode order(long id, User user, int quantity) throws Exception {
        String body = perform(post("/api/market/group-buys/" + id + "/payments"), user,
                "{\"quantity\":" + quantity + "}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private ResultActions confirm(User user, String paymentKey, String orderId, long amount) throws Exception {
        return perform(post("/api/market/group-buys/payments/confirm"), user, json.writeValueAsString(
                Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount)));
    }

    private long createGroupBuy(int unitPrice, int target) throws Exception {
        Map<String, Object> body = Map.of("title", "같이 사요", "unitPrice", unitPrice, "targetQuantity", target,
                "deadlineAt", Instant.now().plus(2, ChronoUnit.DAYS).toString());
        String res = perform(post("/api/market/group-buys"), host, json.writeValueAsString(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.paymentRequired").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(res).get("summary").get("id").asLong();
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                                  User user, String body) throws Exception {
        req.header("Authorization", token(user));
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    private String paymentStatus(String orderId) {
        return jdbc.queryForObject("SELECT status FROM group_buy_payments WHERE order_id = ?", String.class, orderId);
    }

    private int participantCount(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM group_buy_participants WHERE group_buy_id = ?",
                Integer.class, id);
    }

    private User verifiedUser(String name) {
        User u = users.save(new User("DEV", name, name));
        jdbc.update("UPDATE users SET verified_at = now() WHERE id = ?", u.getId());
        return u;
    }

    private String token(User u) {
        return "Bearer " + jwt.issueStudent(u.getId(), true);
    }
}
