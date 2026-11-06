package com.moagwa.market;

import com.moagwa.auth.CurrentUser;
import com.moagwa.market.MarketDto.ItemDetail;
import com.moagwa.market.MarketDto.ItemPage;
import com.moagwa.market.MarketDto.ItemRequest;
import com.moagwa.market.MarketDto.RequestCreated;
import com.moagwa.market.MarketDto.RequestView;
import com.moagwa.market.MarketDto.StatusRequest;
import com.moagwa.market.MarketDto.TradeRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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

    private final MarketItemService items;
    private final TradeRequestService requests;

    @GetMapping("/items")
    ItemPage list(@RequestParam(required = false) String category, @RequestParam(required = false) String status,
                  @RequestParam(required = false) String q, @RequestParam(defaultValue = "latest") String sort,
                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                  Authentication auth) {
        return items.search(category, status, q, sort, page, size, me(auth));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    ItemDetail create(@Valid @RequestBody ItemRequest body, Authentication auth) {
        return items.create(body, me(auth));
    }

    @GetMapping("/items/{id}")
    ItemDetail get(@PathVariable long id, Authentication auth) {
        return items.view(id, me(auth));
    }

    @PutMapping("/items/{id}")
    ItemDetail update(@PathVariable long id, @Valid @RequestBody ItemRequest body, Authentication auth) {
        return items.update(id, body, me(auth));
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id, Authentication auth) {
        items.delete(id, me(auth));
    }

    @PostMapping("/items/{id}/status")
    ItemDetail changeStatus(@PathVariable long id, @Valid @RequestBody StatusRequest body, Authentication auth) {
        return items.changeStatus(id, body.status(), me(auth));
    }

    @PutMapping("/items/{id}/likes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void like(@PathVariable long id, Authentication auth) {
        items.like(id, me(auth));
    }

    @DeleteMapping("/items/{id}/likes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlike(@PathVariable long id, Authentication auth) {
        items.unlike(id, me(auth));
    }

    @PostMapping("/items/{id}/requests")
    @ResponseStatus(HttpStatus.CREATED)
    RequestCreated request(@PathVariable long id, @Valid @RequestBody TradeRequest body, Authentication auth) {
        return requests.create(id, body.message(), me(auth));
    }

    @GetMapping("/items/{id}/requests")
    List<RequestView> requests(@PathVariable long id, Authentication auth) {
        return requests.findForItem(id, me(auth));
    }

    @PostMapping("/requests/{id}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void accept(@PathVariable long id, Authentication auth) {
        requests.accept(id, me(auth));
    }

    private static long me(Authentication auth) {
        return CurrentUser.from(auth).id();
    }
}
