package com.moagwa.market;

import com.moagwa.market.MarketDto.GroupBuySummary;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class GroupBuyRepository {

    private static final String RECRUITING = "g.status = 'RECRUITING' AND g.deadline_at > now()";
    private static final String CLOSED = "(g.status <> 'RECRUITING' OR g.deadline_at <= now())";

    /** 마감 시각이 지난 모집은 저장된 status와 상관없이 CLOSED로 본다. */
    private static final String EFFECTIVE_STATUS = "CASE WHEN g.status = 'RECRUITING' AND g.deadline_at <= now() "
            + "THEN 'CLOSED' ELSE g.status END";

    private final NamedParameterJdbcTemplate jdbc;

    record NewGroupBuy(String title, String description, int unitPrice, int targetQuantity, Instant deadlineAt,
                       String pickupPlace, String imageUrl) {
    }

    record DetailExtra(String description, long hostId, int participants) {
    }

    record LockedGroupBuy(int targetQuantity, String status, Instant deadlineAt) {
    }

    List<GroupBuySummary> findRecruiting(String query, long viewerId, PageWindow window) {
        return search(RECRUITING, "g.deadline_at ASC, g.id DESC", query, viewerId, window);
    }

    List<GroupBuySummary> findClosed(String query, long viewerId, PageWindow window) {
        return search(CLOSED, "g.created_at DESC, g.id DESC", query, viewerId, window);
    }

    List<GroupBuySummary> findByMember(long userId, int limit) {
        return summaries("(g.host_id = :me OR EXISTS (SELECT 1 FROM group_buy_participants mp "
                        + "WHERE mp.group_buy_id = g.id AND mp.user_id = :me))",
                new MapSqlParameterSource(), "g.created_at DESC, g.id DESC", userId, limit, 0);
    }

    Optional<GroupBuySummary> findSummary(long id, long viewerId) {
        return summaries("g.id = :id", new MapSqlParameterSource("id", id), "g.id", viewerId, 1, 0)
                .stream().findFirst();
    }

    Optional<DetailExtra> findDetailExtra(long id) {
        return jdbc.query("""
                SELECT g.description, g.host_id,
                       (SELECT count(*) FROM group_buy_participants q WHERE q.group_buy_id = g.id) AS participants
                  FROM group_buys g WHERE g.id = :id""", new MapSqlParameterSource("id", id),
                (rs, n) -> new DetailExtra(rs.getString("description"), rs.getLong("host_id"),
                        rs.getInt("participants"))).stream().findFirst();
    }

    Optional<Long> findHostId(long id) {
        return jdbc.query("SELECT host_id FROM group_buys WHERE id = :id", new MapSqlParameterSource("id", id),
                (rs, n) -> rs.getLong("host_id")).stream().findFirst();
    }

    long insert(long hostId, NewGroupBuy groupBuy) {
        return jdbc.queryForObject("""
                INSERT INTO group_buys (host_id, title, description, unit_price, target_quantity, deadline_at,
                                        pickup_place, image_url)
                VALUES (:host, :title, :description, :price, :target, :deadline, :place, :image) RETURNING id""",
                new MapSqlParameterSource().addValue("host", hostId).addValue("title", groupBuy.title())
                        .addValue("description", groupBuy.description()).addValue("price", groupBuy.unitPrice())
                        .addValue("target", groupBuy.targetQuantity())
                        .addValue("deadline", Timestamp.from(groupBuy.deadlineAt()))
                        .addValue("place", groupBuy.pickupPlace()).addValue("image", groupBuy.imageUrl()),
                Long.class);
    }

    /** 호출하는 트랜잭션이 끝날 때까지 모집 행을 잠가 목표 수량을 넘지 않게 한다. */
    Optional<LockedGroupBuy> lock(long id) {
        return jdbc.query("SELECT target_quantity, status, deadline_at FROM group_buys WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id),
                (rs, n) -> new LockedGroupBuy(rs.getInt("target_quantity"), rs.getString("status"),
                        rs.getTimestamp("deadline_at").toInstant())).stream().findFirst();
    }

    int sumQuantityOfOthers(long id, long userId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0) FROM group_buy_participants
                 WHERE group_buy_id = :id AND user_id <> :user""", memberParams(id, userId), Integer.class);
    }

    void saveParticipant(long id, long userId, int quantity) {
        jdbc.update("""
                INSERT INTO group_buy_participants (group_buy_id, user_id, quantity) VALUES (:id, :user, :quantity)
                ON CONFLICT (group_buy_id, user_id) DO UPDATE SET quantity = :quantity""",
                memberParams(id, userId).addValue("quantity", quantity));
    }

    void deleteParticipant(long id, long userId) {
        jdbc.update("DELETE FROM group_buy_participants WHERE group_buy_id = :id AND user_id = :user",
                memberParams(id, userId));
    }

    boolean isRecruiting(long id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM group_buys g WHERE g.id = :id AND " + RECRUITING,
                new MapSqlParameterSource("id", id), Integer.class);
        return count != null && count > 0;
    }

    void close(long id) {
        jdbc.update("UPDATE group_buys SET status = 'CLOSED' WHERE id = :id AND status = 'RECRUITING'",
                new MapSqlParameterSource("id", id));
    }

    private List<GroupBuySummary> search(String statusCondition, String orderBy, String query, long viewerId,
                                         PageWindow window) {
        String where = statusCondition;
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (query != null && !query.isBlank()) {
            where += " AND (g.title ILIKE :q OR g.description ILIKE :q)";
            params.addValue("q", MarketInput.likePattern(query));
        }
        return summaries(where, params, orderBy, viewerId, window.fetchLimit(), window.offset());
    }

    /** where와 orderBy는 이 클래스 안의 고정된 문자열만 받는다. 사용자 입력은 항상 파라미터로 넘긴다. */
    private List<GroupBuySummary> summaries(String where, MapSqlParameterSource params, String orderBy, long viewerId,
                                            int limit, int offset) {
        params.addValue("me", viewerId).addValue("limit", limit).addValue("offset", offset);
        String sql = "SELECT g.id, g.title, g.unit_price, g.target_quantity, g.deadline_at, g.pickup_place, "
                + "g.image_url, g.created_at, u.nickname AS host_nickname, " + EFFECTIVE_STATUS + " AS eff_status, "
                + "COALESCE((SELECT sum(q.quantity) FROM group_buy_participants q WHERE q.group_buy_id = g.id), 0) "
                + "AS joined, "
                + "COALESCE((SELECT q.quantity FROM group_buy_participants q "
                + "WHERE q.group_buy_id = g.id AND q.user_id = :me), 0) AS my_quantity "
                + "FROM group_buys g JOIN users u ON u.id = g.host_id WHERE " + where
                + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, (rs, n) -> new GroupBuySummary(rs.getLong("id"), rs.getString("title"),
                rs.getInt("unit_price"), rs.getInt("target_quantity"), rs.getInt("joined"),
                rs.getTimestamp("deadline_at").toInstant().toString(), rs.getString("pickup_place"),
                rs.getString("image_url"), rs.getString("eff_status"), rs.getString("host_nickname"),
                rs.getInt("my_quantity"), rs.getTimestamp("created_at").toInstant().toString()));
    }

    private static MapSqlParameterSource memberParams(long id, long userId) {
        return new MapSqlParameterSource().addValue("id", id).addValue("user", userId);
    }
}
