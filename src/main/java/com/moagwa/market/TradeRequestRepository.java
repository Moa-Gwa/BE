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

    record LockedRequest(long itemId, String status) {
    }

    /** 같은 구매자가 이미 요청했다면 빈 값을 돌려준다. */
    Optional<Long> insert(long itemId, long buyerId, String message) {
        return jdbc.query("""
                INSERT INTO market_requests (item_id, buyer_id, message) VALUES (:item, :buyer, :message)
                ON CONFLICT (item_id, buyer_id) DO NOTHING RETURNING id""",
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

    /** 호출하는 트랜잭션이 끝날 때까지 요청 행을 잠근다. */
    Optional<LockedRequest> lock(long id) {
        return jdbc.query("SELECT item_id, status FROM market_requests WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id),
                (rs, n) -> new LockedRequest(rs.getLong("item_id"), rs.getString("status"))).stream().findFirst();
    }

    void markAccepted(long id) {
        jdbc.update("UPDATE market_requests SET status = 'ACCEPTED' WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    void rejectOtherPending(long itemId, long acceptedId) {
        jdbc.update("UPDATE market_requests SET status = 'REJECTED' "
                        + "WHERE item_id = :item AND id <> :id AND status = 'PENDING'",
                new MapSqlParameterSource().addValue("item", itemId).addValue("id", acceptedId));
    }
}
