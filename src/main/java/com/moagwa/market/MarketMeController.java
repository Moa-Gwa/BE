package com.moagwa.market;

import com.moagwa.auth.CurrentUser;
import com.moagwa.market.MarketDto.GroupBuySummary;
import com.moagwa.market.MarketDto.ItemSummary;
import com.moagwa.market.MarketDto.RequestView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 거래: 내가 올린 물품, 관심 물품, 내가 보낸 거래 요청, 내가 만들었거나 참여한 같이 사기. */
@RestController
@RequestMapping("/api/me/market")
@RequiredArgsConstructor
class MarketMeController {

    private static final int LIMIT = 100;

    private final NamedParameterJdbcTemplate jdbc;
    private final MarketRepository repository;

    @GetMapping("/items")
    @Transactional(readOnly = true)
    List<ItemSummary> items(Authentication auth) {
        return repository.items(CurrentUser.from(auth).id(), "i.seller_id = :me", new MapSqlParameterSource(),
                "i.created_at DESC, i.id DESC", LIMIT, 0);
    }

    @GetMapping("/likes")
    @Transactional(readOnly = true)
    List<ItemSummary> likes(Authentication auth) {
        return repository.items(CurrentUser.from(auth).id(),
                "EXISTS (SELECT 1 FROM market_likes ml WHERE ml.item_id = i.id AND ml.user_id = :me)",
                new MapSqlParameterSource(), "i.created_at DESC, i.id DESC", LIMIT, 0);
    }

    @GetMapping("/requests")
    @Transactional(readOnly = true)
    List<RequestView> requests(Authentication auth) {
        return jdbc.query("""
                SELECT r.id, r.item_id, i.title, r.buyer_id, u.nickname, r.message, r.status, r.created_at
                  FROM market_requests r JOIN market_items i ON i.id = r.item_id JOIN users u ON u.id = r.buyer_id
                 WHERE r.buyer_id = :me ORDER BY r.created_at DESC, r.id DESC LIMIT 100""",
                new MapSqlParameterSource("me", CurrentUser.from(auth).id()),
                (rs, n) -> new RequestView(rs.getLong("id"), rs.getLong("item_id"), rs.getString("title"),
                        rs.getLong("buyer_id"), rs.getString("nickname"), rs.getString("message"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant().toString()));
    }

    @GetMapping("/group-buys")
    @Transactional(readOnly = true)
    List<GroupBuySummary> groupBuys(Authentication auth) {
        return repository.groupBuys(CurrentUser.from(auth).id(),
                "(g.host_id = :me OR EXISTS (SELECT 1 FROM group_buy_participants mp "
                        + "WHERE mp.group_buy_id = g.id AND mp.user_id = :me))",
                new MapSqlParameterSource(), "g.created_at DESC, g.id DESC", LIMIT, 0);
    }
}
