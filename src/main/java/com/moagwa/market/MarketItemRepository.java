package com.moagwa.market;

import com.moagwa.market.MarketDto.ItemDetail;
import com.moagwa.market.MarketDto.ItemSummary;
import com.moagwa.market.MarketDto.Seller;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MarketItemRepository {

    private static final String LATEST_FIRST = "i.created_at DESC, i.id DESC";

    private final NamedParameterJdbcTemplate jdbc;

    record ItemSearch(String category, String status, String query, String sort) {
    }

    record ItemFields(String title, String description, int price, String category, String tradePlace) {
    }

    record SaleState(long sellerId, String status) {
    }

    List<ItemSummary> search(ItemSearch search, long viewerId, PageWindow window) {
        StringBuilder where = new StringBuilder("TRUE");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (search.category() != null) {
            where.append(" AND i.category = :category");
            params.addValue("category", search.category());
        }
        if (search.status() != null) {
            where.append(" AND i.status = :status");
            params.addValue("status", search.status());
        }
        if (search.query() != null) {
            where.append(" AND (i.title ILIKE :q OR i.description ILIKE :q)");
            params.addValue("q", MarketInput.likePattern(search.query()));
        }
        String orderBy = switch (search.sort()) {
            case "price" -> "i.price ASC, i.id DESC";
            case "popular" -> "i.like_count DESC, i.id DESC";
            default -> LATEST_FIRST;
        };
        return summaries(where.toString(), params, orderBy, viewerId, window.fetchLimit(), window.offset());
    }

    List<ItemSummary> findBySeller(long sellerId, int limit) {
        return summaries("i.seller_id = :me", new MapSqlParameterSource(), LATEST_FIRST, sellerId, limit, 0);
    }

    List<ItemSummary> findLikedBy(long userId, int limit) {
        return summaries("EXISTS (SELECT 1 FROM market_likes ml WHERE ml.item_id = i.id AND ml.user_id = :me)",
                new MapSqlParameterSource(), LATEST_FIRST, userId, limit, 0);
    }

    Optional<ItemDetail> findDetail(long id, long viewerId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id).addValue("me", viewerId);
        List<String> images = findImages(id);
        return jdbc.query("""
                SELECT i.id, i.title, i.description, i.price, i.category, i.status, i.trade_place, i.like_count,
                       i.view_count, i.created_at, i.seller_id, u.nickname, o.name AS department,
                       EXISTS (SELECT 1 FROM market_likes l WHERE l.item_id = i.id AND l.user_id = :me) AS liked
                  FROM market_items i JOIN users u ON u.id = i.seller_id
                  LEFT JOIN organizations o ON o.id = u.primary_department_id
                 WHERE i.id = :id""", params, (rs, n) -> toDetail(rs, images, viewerId)).stream().findFirst();
    }

    Optional<Long> findSellerId(long id) {
        return jdbc.query("SELECT seller_id FROM market_items WHERE id = :id", new MapSqlParameterSource("id", id),
                (rs, n) -> rs.getLong("seller_id")).stream().findFirst();
    }

    Optional<SaleState> findSaleState(long id) {
        return jdbc.query("SELECT seller_id, status FROM market_items WHERE id = :id",
                new MapSqlParameterSource("id", id),
                (rs, n) -> new SaleState(rs.getLong("seller_id"), rs.getString("status"))).stream().findFirst();
    }

    long insert(long sellerId, ItemFields fields) {
        return jdbc.queryForObject("""
                INSERT INTO market_items (seller_id, title, description, price, category, trade_place)
                VALUES (:me, :title, :description, :price, :category, :place) RETURNING id""",
                params(fields).addValue("me", sellerId), Long.class);
    }

    void update(long id, ItemFields fields) {
        jdbc.update("""
                UPDATE market_items SET title = :title, description = :description, price = :price,
                       category = :category, trade_place = :place, updated_at = now() WHERE id = :id""",
                params(fields).addValue("id", id));
    }

    void delete(long id) {
        jdbc.update("DELETE FROM market_items WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    void updateStatus(long id, String status) {
        jdbc.update("UPDATE market_items SET status = :status, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("status", status).addValue("id", id));
    }

    /** 판매 중일 때만 예약 중으로 바꾼다. 바뀌지 않았으면 false를 돌려준다. */
    boolean reserveIfOnSale(long id) {
        return jdbc.update("UPDATE market_items SET status = 'RESERVED', updated_at = now() "
                + "WHERE id = :id AND status = 'ON_SALE'", new MapSqlParameterSource("id", id)) > 0;
    }

    /** 판매자가 자기 물품을 볼 때는 조회수를 올리지 않는다. */
    void increaseViewCount(long id, long viewerId) {
        jdbc.update("UPDATE market_items SET view_count = view_count + 1 WHERE id = :id AND seller_id <> :me",
                new MapSqlParameterSource().addValue("id", id).addValue("me", viewerId));
    }

    void addImages(long itemId, List<String> urls) {
        if (urls == null) {
            return;
        }
        for (int i = 0; i < urls.size(); i++) {
            jdbc.update("INSERT INTO market_item_images (item_id, url, sort_order) VALUES (:id, :url, :order)",
                    new MapSqlParameterSource().addValue("id", itemId).addValue("url", urls.get(i))
                            .addValue("order", i));
        }
    }

    void deleteImages(long itemId) {
        jdbc.update("DELETE FROM market_item_images WHERE item_id = :id", new MapSqlParameterSource("id", itemId));
    }

    void addLike(long itemId, long userId) {
        MapSqlParameterSource params = likeParams(itemId, userId);
        int inserted = jdbc.update(
                "INSERT INTO market_likes (user_id, item_id) VALUES (:me, :id) ON CONFLICT DO NOTHING", params);
        if (inserted > 0) {
            jdbc.update("UPDATE market_items SET like_count = like_count + 1 WHERE id = :id", params);
        }
    }

    void removeLike(long itemId, long userId) {
        MapSqlParameterSource params = likeParams(itemId, userId);
        int deleted = jdbc.update("DELETE FROM market_likes WHERE user_id = :me AND item_id = :id", params);
        if (deleted > 0) {
            jdbc.update("UPDATE market_items SET like_count = GREATEST(like_count - 1, 0) WHERE id = :id", params);
        }
    }

    /** where와 orderBy는 이 클래스 안의 고정된 문자열만 받는다. 사용자 입력은 항상 파라미터로 넘긴다. */
    private List<ItemSummary> summaries(String where, MapSqlParameterSource params, String orderBy, long viewerId,
                                        int limit, int offset) {
        params.addValue("me", viewerId).addValue("limit", limit).addValue("offset", offset);
        String sql = "SELECT i.id, i.title, i.price, i.category, i.status, i.trade_place, i.like_count, i.created_at, "
                + "(SELECT m.url FROM market_item_images m WHERE m.item_id = i.id ORDER BY m.sort_order, m.id LIMIT 1) "
                + "AS thumbnail, "
                + "EXISTS (SELECT 1 FROM market_likes l WHERE l.item_id = i.id AND l.user_id = :me) AS liked "
                + "FROM market_items i WHERE " + where + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, (rs, n) -> new ItemSummary(rs.getLong("id"), rs.getString("title"),
                rs.getInt("price"), rs.getString("category"), rs.getString("status"), rs.getString("trade_place"),
                rs.getString("thumbnail"), rs.getInt("like_count"), rs.getBoolean("liked"),
                rs.getTimestamp("created_at").toInstant().toString()));
    }

    private List<String> findImages(long itemId) {
        return jdbc.query("SELECT url FROM market_item_images WHERE item_id = :id ORDER BY sort_order, id",
                new MapSqlParameterSource("id", itemId), (rs, n) -> rs.getString("url"));
    }

    private static ItemDetail toDetail(ResultSet rs, List<String> images, long viewerId) throws SQLException {
        long sellerId = rs.getLong("seller_id");
        return new ItemDetail(rs.getLong("id"), rs.getString("title"), rs.getString("description"), rs.getInt("price"),
                rs.getString("category"), rs.getString("status"), rs.getString("trade_place"), images,
                rs.getInt("like_count"), rs.getInt("view_count"), rs.getBoolean("liked"), sellerId == viewerId,
                new Seller(sellerId, rs.getString("nickname"), rs.getString("department")),
                rs.getTimestamp("created_at").toInstant().toString());
    }

    private static MapSqlParameterSource params(ItemFields fields) {
        return new MapSqlParameterSource().addValue("title", fields.title())
                .addValue("description", fields.description()).addValue("price", fields.price())
                .addValue("category", fields.category()).addValue("place", fields.tradePlace());
    }

    private static MapSqlParameterSource likeParams(long itemId, long userId) {
        return new MapSqlParameterSource().addValue("id", itemId).addValue("me", userId);
    }
}
