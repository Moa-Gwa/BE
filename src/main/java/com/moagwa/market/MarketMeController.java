package com.moagwa.market;

import com.moagwa.auth.CurrentUser;
import com.moagwa.market.MarketDto.GroupBuySummary;
import com.moagwa.market.MarketDto.ItemSummary;
import com.moagwa.market.MarketDto.RequestView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 거래: 내가 올린 물품, 관심 물품, 내가 보낸 거래 요청, 내가 만들었거나 참여한 같이 사기. */
@RestController
@RequestMapping("/api/me/market")
@RequiredArgsConstructor
class MarketMeController {

    private final MarketItemService items;
    private final TradeRequestService requests;
    private final GroupBuyService groupBuys;

    @GetMapping("/items")
    List<ItemSummary> items(Authentication auth) {
        return items.findMine(me(auth));
    }

    @GetMapping("/likes")
    List<ItemSummary> likes(Authentication auth) {
        return items.findLiked(me(auth));
    }

    @GetMapping("/requests")
    List<RequestView> requests(Authentication auth) {
        return requests.findMine(me(auth));
    }

    @GetMapping("/group-buys")
    List<GroupBuySummary> groupBuys(Authentication auth) {
        return groupBuys.findMine(me(auth));
    }

    private static long me(Authentication auth) {
        return CurrentUser.from(auth).id();
    }
}
