package com.moagwa.market;

import com.moagwa.auth.CurrentUser;
import com.moagwa.market.MarketDto.GroupBuyDetail;
import com.moagwa.market.MarketDto.GroupBuyPage;
import com.moagwa.market.MarketDto.GroupBuyRequest;
import com.moagwa.market.MarketDto.JoinRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market/group-buys")
@RequiredArgsConstructor
class GroupBuyController {

    private final GroupBuyService groupBuys;

    @GetMapping
    GroupBuyPage list(@RequestParam(defaultValue = "RECRUITING") String status,
                      @RequestParam(required = false) String q,
                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                      Authentication auth) {
        return groupBuys.search(status, q, page, size, me(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GroupBuyDetail create(@Valid @RequestBody GroupBuyRequest body, Authentication auth) {
        return groupBuys.create(body, me(auth));
    }

    @GetMapping("/{id}")
    GroupBuyDetail get(@PathVariable long id, Authentication auth) {
        return groupBuys.find(id, me(auth));
    }

    @PostMapping("/{id}/join")
    @ResponseStatus(HttpStatus.CREATED)
    GroupBuyDetail join(@PathVariable long id, @Valid @RequestBody JoinRequest body, Authentication auth) {
        return groupBuys.join(id, body.quantity(), me(auth));
    }

    @DeleteMapping("/{id}/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(@PathVariable long id, Authentication auth) {
        groupBuys.leave(id, me(auth));
    }

    @PostMapping("/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(@PathVariable long id, Authentication auth) {
        groupBuys.close(id, me(auth));
    }

    private static long me(Authentication auth) {
        return CurrentUser.from(auth).id();
    }
}
