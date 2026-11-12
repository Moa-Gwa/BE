package com.moagwa.market;

import com.moagwa.market.MarketDto.RequestView;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class TradeRequestRepository {

    private static final String SELECT_VIEW = """
            SELECT r.id, r.item_id, i.title, r.buyer_id, u.nickname, r.message, r.status, r.created_at
              FROM market_requests r JOIN market_items i ON i.id = r.item_id JOIN users u ON u.id = r.buyer_id
            """;

    private static final RowMapper<RequestView> VIEW_MAPPER = (rs, n) -> new RequestView(rs.getLong("id"),
            rs.getLong("item_id"), rs.getString("title"), rs.getLong("buyer_id"), rs.getString("nickname"),
            rs.getString("message"), rs.getString("status"), rs.getTimestamp("created_at").toInstant().toString());

    private final NamedParameterJdbcTemplate jdbc;

    record LockedRequest(long itemId, long buyerId, String status) {
    }

    /** 알림에 쓰는 거래 당사자와 물품 제목 */
    record Parties(long itemId, String itemTitle, long sellerId, long buyerId, String buyerNickname) {
    }

    /**
     * 같은 구매자가 대기 중이거나 수락된 요청을 이미 가지고 있다면 빈 값을 돌려준다.
     * 거절·취소된 요청은 같은 행을 대기 상태로 되돌려 재요청으로 씀(물품당 구매자 한 행 유지)
     */
    Optional<Long> insert(long itemId, long buyerId, String message) {
        return jdbc.query("""
                INSERT INTO market_requests (item_id, buyer_id, message) VALUES (:item, :buyer, :message)
                ON CONFLICT (item_id, buyer_id) DO UPDATE
                   SET status = 'PENDING', message = EXCLUDED.message, created_at = now()
                 WHERE market_requests.status IN ('REJECTED', 'CANCELED')
                RETURNING id""",
                new MapSqlParameterSource().addValue("item", itemId).addValue("buyer", buyerId)
                        .addValue("message", message),
                (rs, n) -> rs.getLong("id")).stream().findFirst();
    }

    List<RequestView> findByItem(long itemId) {
        return jdbc.query(SELECT_VIEW + " WHERE r.item_id = :item ORDER BY r.created_at DESC, r.id DESC",
                new MapSqlParameterSource("item", itemId), VIEW_MAPPER);
    }

    List<RequestView> findByBuyer(long buyerId, int limit) {
        return jdbc.query(SELECT_VIEW + " WHERE r.buyer_id = :buyer ORDER BY r.created_at DESC, r.id DESC LIMIT :limit",
                new MapSqlParameterSource().addValue("buyer", buyerId).addValue("limit", limit), VIEW_MAPPER);
    }

    Optional<Long> findItemId(long id) {
        return jdbc.queryForList("SELECT item_id FROM market_requests WHERE id = :id",
                new MapSqlParameterSource("id", id), Long.class).stream().findFirst();
    }

    /** 호출하는 트랜잭션이 끝날 때까지 요청 행을 잠근다. 물품 행을 먼저 잠근 뒤 호출한다. */
    Optional<LockedRequest> lock(long id) {
        return jdbc.query("SELECT item_id, buyer_id, status FROM market_requests WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id),
                (rs, n) -> new LockedRequest(rs.getLong("item_id"), rs.getLong("buyer_id"), rs.getString("status")))
                .stream().findFirst();
    }

    Parties findParties(long id) {
        return jdbc.queryForObject("""
                SELECT r.item_id, i.title, i.seller_id, r.buyer_id, u.nickname
                  FROM market_requests r JOIN market_items i ON i.id = r.item_id JOIN users u ON u.id = r.buyer_id
                 WHERE r.id = :id""", new MapSqlParameterSource("id", id),
                (rs, n) -> new Parties(rs.getLong("item_id"), rs.getString("title"), rs.getLong("seller_id"),
                        rs.getLong("buyer_id"), rs.getString("nickname")));
    }

    void updateStatus(long id, String status) {
        jdbc.update("UPDATE market_requests SET status = :status WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status));
    }

    /** 판매자가 예약을 풀면 수락했던 요청은 취소로 바뀜 */
    void cancelAccepted(long itemId) {
        jdbc.update("UPDATE market_requests SET status = 'CANCELED' WHERE item_id = :item AND status = 'ACCEPTED'",
                new MapSqlParameterSource("item", itemId));
    }

    void rejectOtherPending(long itemId, long acceptedId) {
        jdbc.update("UPDATE market_requests SET status = 'REJECTED' "
                        + "WHERE item_id = :item AND id <> :id AND status = 'PENDING'",
                new MapSqlParameterSource().addValue("item", itemId).addValue("id", acceptedId));
    }
}
