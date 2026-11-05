package com.moagwa.market;

import com.moagwa.auth.CurrentUser;
import com.moagwa.common.exception.ApiException;
import com.moagwa.market.MarketDto.ItemDetail;
import com.moagwa.market.MarketDto.ItemPage;
import com.moagwa.market.MarketDto.ItemSummary;
import com.moagwa.market.MarketDto.RequestView;
import com.moagwa.market.MarketDto.Seller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 중고거래. 재학생 인증을 마친 학생만 쓸 수 있고(SecurityConfig), 판매자 id는 항상 토큰에서 가져온다. */
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
class MarketItemController {

    private static final Set<String> CATEGORIES = Set.of("BOOK", "ELECTRONICS", "LIVING", "CLOTHES", "ETC");
    private static final Set<String> STATUSES = Set.of("ON_SALE", "RESERVED", "SOLD");

    private final NamedParameterJdbcTemplate jdbc;
    private final MarketRepository repository;

    @GetMapping("/items")
    @Transactional(readOnly = true)
    ItemPage list(@RequestParam(required = false) String category, @RequestParam(required = false) String status,
                  @RequestParam(required = false) String q, @RequestParam(defaultValue = "latest") String sort,
                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                  Authentication auth) {
        StringBuilder where = new StringBuilder("TRUE");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (category != null && !category.isBlank()) {
            requireIn(CATEGORIES, category, "카테고리");
            where.append(" AND i.category = :category");
            p.addValue("category", category);
        }
        if (status != null && !status.isBlank()) {
            requireIn(STATUSES, status, "판매 상태");
            where.append(" AND i.status = :status");
            p.addValue("status", status);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND i.title ILIKE :q");
            p.addValue("q", MarketRepository.like(q));
        }
        String order = switch (sort) {
            case "price" -> "i.price ASC, i.id DESC";
            case "popular" -> "i.like_count DESC, i.id DESC";
            default -> "i.created_at DESC, i.id DESC";
        };
        int s = MarketRepository.pageSize(size);
        int pg = Math.max(page, 0);
        List<ItemSummary> rows = repository.items(CurrentUser.from(auth).id(), where.toString(), p, order, s + 1, pg * s);
        boolean hasNext = rows.size() > s;
        return new ItemPage(hasNext ? rows.subList(0, s) : rows, pg, s, hasNext);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ItemDetail create(@Valid @RequestBody ItemRequest body, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        validate(body);
        MapSqlParameterSource p = fields(body).addValue("me", me);
        Long id = jdbc.queryForObject("""
                INSERT INTO market_items (seller_id, title, description, price, category, trade_place)
                VALUES (:me, :title, :description, :price, :category, :place) RETURNING id""", p, Long.class);
        saveImages(id, body.imageUrls());
        return detail(id, me);
    }

    @GetMapping("/items/{id}")
    @Transactional
    ItemDetail get(@PathVariable long id, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        jdbc.update("UPDATE market_items SET view_count = view_count + 1 WHERE id = :id AND seller_id <> :me",
                new MapSqlParameterSource().addValue("id", id).addValue("me", me));
        return detail(id, me);
    }

    @PutMapping("/items/{id}")
    @Transactional
    ItemDetail update(@PathVariable long id, @Valid @RequestBody ItemRequest body, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        requireSeller(id, me);
        validate(body);
        jdbc.update("""
                UPDATE market_items SET title = :title, description = :description, price = :price,
                       category = :category, trade_place = :place, updated_at = now() WHERE id = :id""",
                fields(body).addValue("id", id));
        jdbc.update("DELETE FROM market_item_images WHERE item_id = :id", new MapSqlParameterSource("id", id));
        saveImages(id, body.imageUrls());
        return detail(id, me);
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable long id, Authentication auth) {
        requireSeller(id, CurrentUser.from(auth).id());
        jdbc.update("DELETE FROM market_items WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    @PostMapping("/items/{id}/status")
    @Transactional
    ItemDetail changeStatus(@PathVariable long id, @Valid @RequestBody StatusRequest body, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        requireSeller(id, me);
        requireIn(STATUSES, body.status(), "판매 상태");
        jdbc.update("UPDATE market_items SET status = :s, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("s", body.status()).addValue("id", id));
        return detail(id, me);
    }

    @PutMapping("/items/{id}/likes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void like(@PathVariable long id, Authentication auth) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("me", CurrentUser.from(auth).id());
        requireExists(id);
        int inserted = jdbc.update(
                "INSERT INTO market_likes (user_id, item_id) VALUES (:me, :id) ON CONFLICT DO NOTHING", p);
        if (inserted > 0) {
            jdbc.update("UPDATE market_items SET like_count = like_count + 1 WHERE id = :id", p);
        }
    }

    @DeleteMapping("/items/{id}/likes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void unlike(@PathVariable long id, Authentication auth) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("me", CurrentUser.from(auth).id());
        int deleted = jdbc.update("DELETE FROM market_likes WHERE user_id = :me AND item_id = :id", p);
        if (deleted > 0) {
            jdbc.update("UPDATE market_items SET like_count = GREATEST(like_count - 1, 0) WHERE id = :id", p);
        }
    }

    @PostMapping("/items/{id}/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, Object> request(@PathVariable long id, @Valid @RequestBody TradeRequest body, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT seller_id, status FROM market_items WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw ApiException.notFound("물품을 찾을 수 없습니다.");
        }
        if (((Number) rows.get(0).get("seller_id")).longValue() == me) {
            throw ApiException.badRequest("내 물품에는 거래를 요청할 수 없습니다.");
        }
        if (!"ON_SALE".equals(rows.get(0).get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "판매 중인 물품에만 거래를 요청할 수 있습니다.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("me", me)
                .addValue("msg", body.message() == null ? null : body.message().trim());
        Long requestId = jdbc.query("""
                INSERT INTO market_requests (item_id, buyer_id, message) VALUES (:id, :me, :msg)
                ON CONFLICT (item_id, buyer_id) DO NOTHING RETURNING id""", p, rs -> rs.next() ? rs.getLong(1) : null);
        if (requestId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 거래를 요청했습니다.");
        }
        return Map.of("id", requestId, "status", "PENDING");
    }

    @GetMapping("/items/{id}/requests")
    @Transactional(readOnly = true)
    List<RequestView> requests(@PathVariable long id, Authentication auth) {
        requireSeller(id, CurrentUser.from(auth).id());
        return jdbc.query("""
                SELECT r.id, r.item_id, i.title, r.buyer_id, u.nickname, r.message, r.status, r.created_at
                  FROM market_requests r JOIN market_items i ON i.id = r.item_id JOIN users u ON u.id = r.buyer_id
                 WHERE r.item_id = :id ORDER BY r.created_at DESC, r.id DESC""", new MapSqlParameterSource("id", id),
                (rs, n) -> new RequestView(rs.getLong("id"), rs.getLong("item_id"), rs.getString("title"),
                        rs.getLong("buyer_id"), rs.getString("nickname"), rs.getString("message"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant().toString()));
    }

    /** 판매자가 요청 하나를 수락한다. 물품은 예약 중이 되고 나머지 대기 요청은 거절된다. */
    @PostMapping("/requests/{id}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void accept(@PathVariable long id, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT item_id, status FROM market_requests WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw ApiException.notFound("거래 요청을 찾을 수 없습니다.");
        }
        long itemId = ((Number) rows.get(0).get("item_id")).longValue();
        requireSeller(itemId, me);
        if (!"PENDING".equals(rows.get(0).get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 처리된 요청입니다.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("item", itemId);
        int reserved = jdbc.update(
                "UPDATE market_items SET status = 'RESERVED', updated_at = now() WHERE id = :item AND status = 'ON_SALE'", p);
        if (reserved == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "판매 중인 물품만 수락할 수 있습니다.");
        }
        jdbc.update("UPDATE market_requests SET status = 'ACCEPTED' WHERE id = :id", p);
        jdbc.update("UPDATE market_requests SET status = 'REJECTED' WHERE item_id = :item AND id <> :id AND status = 'PENDING'", p);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private ItemDetail detail(long id, long me) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("me", me);
        List<ItemDetail> rows = jdbc.query("""
                SELECT i.id, i.title, i.description, i.price, i.category, i.status, i.trade_place, i.like_count,
                       i.view_count, i.created_at, i.seller_id, u.nickname, o.name AS department,
                       EXISTS (SELECT 1 FROM market_likes l WHERE l.item_id = i.id AND l.user_id = :me) AS liked
                  FROM market_items i JOIN users u ON u.id = i.seller_id
                  LEFT JOIN organizations o ON o.id = u.primary_department_id
                 WHERE i.id = :id""", p, (rs, n) -> new ItemDetail(rs.getLong("id"), rs.getString("title"),
                rs.getString("description"), rs.getInt("price"), rs.getString("category"), rs.getString("status"),
                rs.getString("trade_place"), List.of(), rs.getInt("like_count"), rs.getInt("view_count"),
                rs.getBoolean("liked"), rs.getLong("seller_id") == me,
                new Seller(rs.getLong("seller_id"), rs.getString("nickname"), rs.getString("department")),
                rs.getTimestamp("created_at").toInstant().toString()));
        if (rows.isEmpty()) {
            throw ApiException.notFound("물품을 찾을 수 없습니다.");
        }
        List<String> images = jdbc.query(
                "SELECT url FROM market_item_images WHERE item_id = :id ORDER BY sort_order, id", p,
                (rs, n) -> rs.getString("url"));
        ItemDetail d = rows.get(0);
        return new ItemDetail(d.id(), d.title(), d.description(), d.price(), d.category(), d.status(), d.tradePlace(),
                images, d.likeCount(), d.viewCount(), d.liked(), d.mine(), d.seller(), d.createdAt());
    }

    private void requireExists(long id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM market_items WHERE id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        if (n == null || n == 0) {
            throw ApiException.notFound("물품을 찾을 수 없습니다.");
        }
    }

    private void requireSeller(long itemId, long me) {
        List<Long> rows = jdbc.query("SELECT seller_id FROM market_items WHERE id = :id",
                new MapSqlParameterSource("id", itemId), (rs, n) -> rs.getLong("seller_id"));
        if (rows.isEmpty()) {
            throw ApiException.notFound("물품을 찾을 수 없습니다.");
        }
        if (rows.get(0) != me) {
            throw ApiException.forbidden("내가 올린 물품만 바꿀 수 있습니다.");
        }
    }

    private static void requireIn(Set<String> allowed, String value, String label) {
        if (!allowed.contains(value)) {
            throw ApiException.badRequest("올바르지 않은 " + label + "입니다.");
        }
    }

    private static void validate(ItemRequest body) {
        requireIn(CATEGORIES, body.category(), "카테고리");
        MarketRepository.requireUploadUrls(body.imageUrls());
    }

    private static MapSqlParameterSource fields(ItemRequest body) {
        return new MapSqlParameterSource().addValue("title", body.title().trim())
                .addValue("description", body.description() == null ? "" : body.description().trim())
                .addValue("price", body.price()).addValue("category", body.category())
                .addValue("place", body.tradePlace() == null || body.tradePlace().isBlank() ? null : body.tradePlace().trim());
    }

    private void saveImages(long itemId, List<String> urls) {
        if (urls == null) {
            return;
        }
        for (int i = 0; i < urls.size(); i++) {
            jdbc.update("INSERT INTO market_item_images (item_id, url, sort_order) VALUES (:id, :url, :o)",
                    new MapSqlParameterSource().addValue("id", itemId).addValue("url", urls.get(i)).addValue("o", i));
        }
    }

    record ItemRequest(@NotBlank @Size(max = 60) String title, @Size(max = 2000) String description,
                       @NotNull @Min(0) @Max(100_000_000) Integer price, @NotBlank String category,
                       @Size(max = 100) String tradePlace, @Size(max = 10) List<String> imageUrls) {
    }

    record StatusRequest(@NotBlank String status) {
    }

    record TradeRequest(@Size(max = 200) String message) {
    }
}
