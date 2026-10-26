package com.moagwa.market;

import com.moagwa.auth.CurrentUser;
import com.moagwa.common.exception.ApiException;
import com.moagwa.market.MarketDto.GroupBuyDetail;
import com.moagwa.market.MarketDto.GroupBuyPage;
import com.moagwa.market.MarketDto.GroupBuySummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 같이 사기(공동구매). 목표 수량을 정해 모집하고, 마감 전까지 참여·취소할 수 있다. */
@RestController
@RequestMapping("/api/market/group-buys")
@RequiredArgsConstructor
class GroupBuyController {

    private static final int MAX_PER_PERSON = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final MarketRepository repository;

    @GetMapping
    @Transactional(readOnly = true)
    GroupBuyPage list(@RequestParam(defaultValue = "RECRUITING") String status,
                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                      Authentication auth) {
        String where;
        String order;
        if ("RECRUITING".equals(status)) {
            where = "g.status = 'RECRUITING' AND g.deadline_at > now()";
            order = "g.deadline_at ASC, g.id DESC";
        } else if ("CLOSED".equals(status)) {
            where = "(g.status <> 'RECRUITING' OR g.deadline_at <= now())";
            order = "g.created_at DESC, g.id DESC";
        } else {
            throw ApiException.badRequest("올바르지 않은 모집 상태입니다.");
        }
        int s = MarketRepository.pageSize(size);
        int pg = Math.max(page, 0);
        List<GroupBuySummary> rows = repository.groupBuys(CurrentUser.from(auth).id(), where,
                new MapSqlParameterSource(), order, s + 1, pg * s);
        boolean hasNext = rows.size() > s;
        return new GroupBuyPage(hasNext ? rows.subList(0, s) : rows, pg, s, hasNext);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    GroupBuyDetail create(@Valid @RequestBody GroupBuyRequest body, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        if (!body.deadlineAt().isAfter(Instant.now())) {
            throw ApiException.badRequest("마감 시각은 지금보다 뒤여야 합니다.");
        }
        MarketRepository.requireUploadUrls(body.imageUrl() == null ? null : List.of(body.imageUrl()));
        Long id = jdbc.queryForObject("""
                INSERT INTO group_buys (host_id, title, description, unit_price, target_quantity, deadline_at,
                                        pickup_place, image_url)
                VALUES (:me, :title, :description, :price, :target, :deadline, :place, :image) RETURNING id""",
                new MapSqlParameterSource().addValue("me", me).addValue("title", body.title().trim())
                        .addValue("description", body.description() == null ? "" : body.description().trim())
                        .addValue("price", body.unitPrice()).addValue("target", body.targetQuantity())
                        .addValue("deadline", Timestamp.from(body.deadlineAt()))
                        .addValue("place", body.pickupPlace() == null || body.pickupPlace().isBlank() ? null
                                : body.pickupPlace().trim())
                        .addValue("image", body.imageUrl()), Long.class);
        return detail(id, me);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    GroupBuyDetail get(@PathVariable long id, Authentication auth) {
        return detail(id, CurrentUser.from(auth).id());
    }

    /** 이미 참여했다면 수량을 바꾼다. 목표 수량을 넘겨서는 참여할 수 없다. */
    @PostMapping("/{id}/join")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    GroupBuyDetail join(@PathVariable long id, @Valid @RequestBody JoinRequest body, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("me", me)
                .addValue("q", body.quantity());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT target_quantity, status, deadline_at FROM group_buys WHERE id = :id FOR UPDATE", p);
        if (rows.isEmpty()) {
            throw ApiException.notFound("모집 글을 찾을 수 없습니다.");
        }
        Map<String, Object> g = rows.get(0);
        boolean open = "RECRUITING".equals(g.get("status"))
                && ((Timestamp) g.get("deadline_at")).toInstant().isAfter(Instant.now());
        if (!open) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 마감된 모집입니다.");
        }
        Integer othersJoined = jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0) FROM group_buy_participants
                 WHERE group_buy_id = :id AND user_id <> :me""", p, Integer.class);
        int target = ((Number) g.get("target_quantity")).intValue();
        if (othersJoined != null && othersJoined + body.quantity() > target) {
            throw new ApiException(HttpStatus.CONFLICT, "남은 수량이 부족합니다.");
        }
        jdbc.update("""
                INSERT INTO group_buy_participants (group_buy_id, user_id, quantity) VALUES (:id, :me, :q)
                ON CONFLICT (group_buy_id, user_id) DO UPDATE SET quantity = :q""", p);
        return detail(id, me);
    }

    @DeleteMapping("/{id}/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void leave(@PathVariable long id, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("me", me);
        Integer open = jdbc.queryForObject(
                "SELECT count(*) FROM group_buys WHERE id = :id AND status = 'RECRUITING' AND deadline_at > now()",
                p, Integer.class);
        if (open == null || open == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "마감된 모집은 참여를 취소할 수 없습니다.");
        }
        jdbc.update("DELETE FROM group_buy_participants WHERE group_buy_id = :id AND user_id = :me", p);
    }

    @PostMapping("/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void close(@PathVariable long id, Authentication auth) {
        long me = CurrentUser.from(auth).id();
        List<Long> host = jdbc.query("SELECT host_id FROM group_buys WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, n) -> rs.getLong("host_id"));
        if (host.isEmpty()) {
            throw ApiException.notFound("모집 글을 찾을 수 없습니다.");
        }
        if (host.get(0) != me) {
            throw ApiException.forbidden("모집을 연 사람만 마감할 수 있습니다.");
        }
        jdbc.update("UPDATE group_buys SET status = 'CLOSED' WHERE id = :id AND status = 'RECRUITING'",
                new MapSqlParameterSource("id", id));
    }

    private GroupBuyDetail detail(long id, long me) {
        List<GroupBuySummary> rows = repository.groupBuys(me, "g.id = :gid",
                new MapSqlParameterSource("gid", id), "g.id", 1, 0);
        if (rows.isEmpty()) {
            throw ApiException.notFound("모집 글을 찾을 수 없습니다.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("gid", id);
        List<Map<String, Object>> extra = jdbc.queryForList("""
                SELECT g.description, g.host_id,
                       (SELECT count(*) FROM group_buy_participants q WHERE q.group_buy_id = g.id) AS participants
                  FROM group_buys g WHERE g.id = :gid""", p);
        Map<String, Object> e = extra.get(0);
        return new GroupBuyDetail(rows.get(0), (String) e.get("description"),
                ((Number) e.get("host_id")).longValue() == me, ((Number) e.get("participants")).intValue());
    }

    record GroupBuyRequest(@NotBlank @Size(max = 60) String title, @Size(max = 2000) String description,
                           @NotNull @Min(0) @Max(100_000_000) Integer unitPrice,
                           @NotNull @Min(2) @Max(1000) Integer targetQuantity, @NotNull Instant deadlineAt,
                           @Size(max = 100) String pickupPlace, @Size(max = 300) String imageUrl) {
    }

    record JoinRequest(@NotNull @Min(1) @Max(MAX_PER_PERSON) Integer quantity) {
    }
}
