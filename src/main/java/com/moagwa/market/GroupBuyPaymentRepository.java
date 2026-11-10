package com.moagwa.market;

import com.moagwa.market.MarketDto.ParticipantView;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class GroupBuyPaymentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    record Order(String orderId, long groupBuyId, long userId, int quantity, int amount, String status,
                 String paymentKey) {
    }

    void insert(String orderId, long groupBuyId, long userId, int quantity, int amount) {
        jdbc.update("""
                INSERT INTO group_buy_payments (order_id, group_buy_id, user_id, quantity, amount)
                VALUES (:order, :gb, :user, :quantity, :amount)""",
                new MapSqlParameterSource().addValue("order", orderId).addValue("gb", groupBuyId)
                        .addValue("user", userId).addValue("quantity", quantity).addValue("amount", amount));
    }

    Optional<Order> lock(String orderId) {
        return jdbc.query("SELECT * FROM group_buy_payments WHERE order_id = :order FOR UPDATE",
                new MapSqlParameterSource("order", orderId), (rs, n) -> new Order(rs.getString("order_id"),
                        rs.getLong("group_buy_id"), rs.getLong("user_id"), rs.getInt("quantity"),
                        rs.getInt("amount"), rs.getString("status"), rs.getString("payment_key")))
                .stream().findFirst();
    }

    /** 결제 완료·승인 중·취소 처리 중인 주문(같은 사람의 중복 결제 방지) */
    boolean hasActiveOrder(long groupBuyId, long userId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM group_buy_payments
                 WHERE group_buy_id = :gb AND user_id = :user AND status IN ('APPROVING', 'PAID', 'CANCELING')""",
                new MapSqlParameterSource().addValue("gb", groupBuyId).addValue("user", userId), Integer.class);
        return n != null && n > 0;
    }

    /** 다른 사람의 승인 중 수량. 승인이 끝나기 전에도 남은 수량에서 미리 제외 */
    int sumApprovingOfOthers(long groupBuyId, long userId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0) FROM group_buy_payments
                 WHERE group_buy_id = :gb AND user_id <> :user AND status = 'APPROVING'""",
                new MapSqlParameterSource().addValue("gb", groupBuyId).addValue("user", userId), Integer.class);
    }

    void markApproving(String orderId, String paymentKey) {
        jdbc.update("""
                UPDATE group_buy_payments SET status = 'APPROVING', payment_key = :key, approving_at = now()
                 WHERE order_id = :order AND status = 'READY'""",
                new MapSqlParameterSource().addValue("order", orderId).addValue("key", paymentKey));
    }

    void markFailed(String orderId) {
        jdbc.update("""
                UPDATE group_buy_payments SET status = 'FAILED'
                 WHERE order_id = :order AND status IN ('APPROVING', 'CANCELING')""",
                new MapSqlParameterSource("order", orderId));
    }

    /** 승인 중 주문의 취소 선점. 선점한 주문은 결제 완료로 바뀌지 않음. 이미 선점된 주문도 true */
    boolean claimCancel(String orderId) {
        return jdbc.update("""
                UPDATE group_buy_payments SET status = 'CANCELING'
                 WHERE order_id = :order AND status IN ('APPROVING', 'CANCELING')""",
                new MapSqlParameterSource("order", orderId)) > 0;
    }

    /** 승인됐지만 참여 확정 전에 취소한 결제 */
    void markApprovalCanceled(String orderId, String reason) {
        jdbc.update("""
                UPDATE group_buy_payments SET status = 'CANCELED', canceled_at = now(), cancel_reason = :reason
                 WHERE order_id = :order AND status = 'CANCELING'""",
                new MapSqlParameterSource().addValue("order", orderId).addValue("reason", reason));
    }

    /** 승인 중으로 오래 남은 주문(승인 후 저장 실패 등). 정리 작업이 결제 취소로 확정 */
    List<Order> findStaleApproving(int minutes, int limit) {
        return jdbc.query("""
                SELECT * FROM group_buy_payments
                 WHERE (status = 'APPROVING' AND approving_at < now() - make_interval(mins => :minutes))
                    OR status = 'CANCELING'
                 ORDER BY id LIMIT :limit""",
                new MapSqlParameterSource().addValue("minutes", minutes).addValue("limit", limit),
                (rs, n) -> new Order(rs.getString("order_id"), rs.getLong("group_buy_id"), rs.getLong("user_id"),
                        rs.getInt("quantity"), rs.getInt("amount"), rs.getString("status"), rs.getString("payment_key")));
    }

    /** 결제 완료 주문의 실제 결제 금액. 참가비 도입 전 주문은 단가 × 수량 */
    Optional<Integer> findPaidAmount(long groupBuyId, long userId) {
        return jdbc.queryForList("""
                SELECT amount FROM group_buy_payments
                 WHERE group_buy_id = :gb AND user_id = :user AND status = 'PAID'""",
                new MapSqlParameterSource().addValue("gb", groupBuyId).addValue("user", userId), Integer.class)
                .stream().findFirst();
    }

    Optional<String> findPaidOrderId(long groupBuyId, long userId) {
        return jdbc.queryForList("""
                SELECT order_id FROM group_buy_payments
                 WHERE group_buy_id = :gb AND user_id = :user AND status = 'PAID'""",
                new MapSqlParameterSource().addValue("gb", groupBuyId).addValue("user", userId), String.class)
                .stream().findFirst();
    }

    /** 승인 중 → 결제 완료. 바뀐 행이 없으면 다른 처리(정리 작업 등)가 먼저 끝난 상태 */
    int markPaid(String orderId) {
        return jdbc.update("""
                UPDATE group_buy_payments SET status = 'PAID', paid_at = now()
                 WHERE order_id = :order AND status = 'APPROVING'""", new MapSqlParameterSource("order", orderId));
    }

    void markCanceled(String orderId, String reason) {
        jdbc.update("""
                UPDATE group_buy_payments SET status = 'CANCELED', canceled_at = now(), cancel_reason = :reason
                 WHERE order_id = :order AND status = 'PAID'""",
                new MapSqlParameterSource().addValue("order", orderId).addValue("reason", reason));
    }

    /** 마감 시각까지 목표 수량을 못 채운 모집을 취소 상태로 바꾸고 바뀐 수 반환 */
    int cancelUnmetGroupBuys() {
        return jdbc.update("""
                UPDATE group_buys g SET status = 'CANCELED'
                 WHERE g.status = 'RECRUITING' AND g.deadline_at <= now()
                   AND (SELECT COALESCE(sum(p.quantity), 0) FROM group_buy_participants p
                         WHERE p.group_buy_id = g.id) < g.target_quantity""", new MapSqlParameterSource());
    }

    /** 취소된 모집의 결제 완료 주문(환불 대상) */
    List<String> findRefundable(long groupBuyId, int limit) {
        String where = groupBuyId > 0 ? " AND g.id = :gb" : "";
        return jdbc.queryForList("""
                SELECT p.order_id FROM group_buy_payments p JOIN group_buys g ON g.id = p.group_buy_id
                 WHERE p.status = 'PAID' AND g.status = 'CANCELED'""" + where + " ORDER BY p.id LIMIT :limit",
                new MapSqlParameterSource().addValue("gb", groupBuyId).addValue("limit", limit), String.class);
    }

    List<ParticipantView> findParticipants(long groupBuyId) {
        return jdbc.query("""
                SELECT u.nickname, q.quantity, q.created_at,
                       EXISTS (SELECT 1 FROM group_buy_payments p WHERE p.group_buy_id = q.group_buy_id
                                  AND p.user_id = q.user_id AND p.status = 'PAID') AS paid
                  FROM group_buy_participants q JOIN users u ON u.id = q.user_id
                 WHERE q.group_buy_id = :gb ORDER BY q.created_at""",
                new MapSqlParameterSource("gb", groupBuyId), (rs, n) -> new ParticipantView(rs.getString("nickname"),
                        rs.getInt("quantity"), rs.getBoolean("paid"), rs.getTimestamp("created_at").toInstant().toString()));
    }
}
