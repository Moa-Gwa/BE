package com.moagwa.market;

import com.moagwa.common.exception.ApiException;
import com.moagwa.market.MarketDto.GroupBuySummary;
import com.moagwa.market.MarketDto.ItemSummary;
import java.sql.Timestamp;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 목록 조회용 SQL을 한곳에 모았다. 조건(where)은 이 클래스를 부르는 쪽이 고정된 문자열로만 넘긴다. */
@Repository
@RequiredArgsConstructor
class MarketRepository {

    /** 마감 시각이 지난 모집은 저장된 status와 상관없이 CLOSED로 본다. */
    static final String GROUP_STATUS = "CASE WHEN g.status = 'RECRUITING' AND g.deadline_at <= now() "
            + "THEN 'CLOSED' ELSE g.status END";

    private final NamedParameterJdbcTemplate jdbc;

    List<ItemSummary> items(long me, String where, MapSqlParameterSource p, String orderBy, int limit, int offset) {
        p.addValue("me", me).addValue("limit", limit).addValue("offset", offset);
        String sql = "SELECT i.id, i.title, i.price, i.category, i.status, i.trade_place, i.like_count, i.created_at, "
                + "(SELECT m.url FROM market_item_images m WHERE m.item_id = i.id ORDER BY m.sort_order, m.id LIMIT 1) "
                + "AS thumbnail, "
                + "EXISTS (SELECT 1 FROM market_likes l WHERE l.item_id = i.id AND l.user_id = :me) AS liked "
                + "FROM market_items i WHERE " + where + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, p, (rs, n) ->
                new ItemSummary(rs.getLong("id"), rs.getString("title"), rs.getInt("price"), rs.getString("category"),
                        rs.getString("status"), rs.getString("trade_place"), rs.getString("thumbnail"),
                        rs.getInt("like_count"), rs.getBoolean("liked"),
                        rs.getTimestamp("created_at").toInstant().toString()));
    }

    List<GroupBuySummary> groupBuys(long me, String where, MapSqlParameterSource p, String orderBy, int limit,
                                    int offset) {
        p.addValue("me", me).addValue("limit", limit).addValue("offset", offset);
        String sql = "SELECT g.id, g.title, g.unit_price, g.target_quantity, g.deadline_at, g.pickup_place, "
                + "g.image_url, g.created_at, u.nickname AS host_nickname, " + GROUP_STATUS + " AS eff_status, "
                + "COALESCE((SELECT sum(q.quantity) FROM group_buy_participants q WHERE q.group_buy_id = g.id), 0) "
                + "AS joined, "
                + "COALESCE((SELECT q.quantity FROM group_buy_participants q "
                + "WHERE q.group_buy_id = g.id AND q.user_id = :me), 0) AS my_quantity "
                + "FROM group_buys g JOIN users u ON u.id = g.host_id WHERE " + where
                + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, p, (rs, n) -> {
            Timestamp deadline = rs.getTimestamp("deadline_at");
            return new GroupBuySummary(rs.getLong("id"), rs.getString("title"), rs.getInt("unit_price"),
                    rs.getInt("target_quantity"), rs.getInt("joined"), deadline.toInstant().toString(),
                    rs.getString("pickup_place"), rs.getString("image_url"), rs.getString("eff_status"),
                    rs.getString("host_nickname"), rs.getInt("my_quantity"),
                    rs.getTimestamp("created_at").toInstant().toString());
        });
    }

    static int pageSize(int size) {
        return Math.min(Math.max(size, 1), 50);
    }

    static String like(String q) {
        return "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** 업로드한 이미지 주소만 허용한다. 외부 주소나 경로 이동은 받지 않는다. */
    static void requireUploadUrls(List<String> urls) {
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            if (url == null || !url.matches("^/uploads/[A-Za-z0-9._-]+$") || url.contains("..")) {
                throw ApiException.badRequest("업로드한 이미지만 첨부할 수 있습니다.");
            }
        }
    }
}
