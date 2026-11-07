package com.moagwa.market;

import com.moagwa.chat.ChatService;
import com.moagwa.common.exception.ApiException;
import com.moagwa.market.MarketDto.RequestCreated;
import com.moagwa.market.MarketDto.RequestView;
import com.moagwa.market.MarketItemRepository.SaleState;
import com.moagwa.market.TradeRequestRepository.LockedRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구매자의 거래 요청과 판매자의 수락. */
@Service
@RequiredArgsConstructor
class TradeRequestService {

    private static final int MY_LIST_LIMIT = 100;

    private final TradeRequestRepository requests;
    private final MarketItemRepository items;
    private final MarketItemService itemService;
    private final ChatService chats;

    @Transactional
    RequestCreated create(long itemId, String message, long me) {
        SaleState item = items.findSaleState(itemId)
                .orElseThrow(() -> ApiException.notFound("물품을 찾을 수 없습니다."));
        if (item.sellerId() == me) {
            throw ApiException.badRequest("내 물품에는 거래를 요청할 수 없습니다.");
        }
        if (!"ON_SALE".equals(item.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "판매 중인 물품에만 거래를 요청할 수 있습니다.");
        }
        long id = requests.insert(itemId, me, message == null ? null : message.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "이미 거래를 요청했습니다."));
        return new RequestCreated(id, "PENDING");
    }

    @Transactional(readOnly = true)
    List<RequestView> findForItem(long itemId, long me) {
        itemService.requireSeller(itemId, me);
        return requests.findByItem(itemId);
    }

    @Transactional(readOnly = true)
    List<RequestView> findMine(long me) {
        return requests.findByBuyer(me, MY_LIST_LIMIT);
    }

    /** 판매자가 요청 하나를 수락한다. 물품은 예약 중이 되고 나머지 대기 요청은 거절된다. */
    @Transactional
    void accept(long id, long me) {
        LockedRequest request = requests.lock(id)
                .orElseThrow(() -> ApiException.notFound("거래 요청을 찾을 수 없습니다."));
        itemService.requireSeller(request.itemId(), me);
        if (!"PENDING".equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 처리된 요청입니다.");
        }
        if (!items.reserveIfOnSale(request.itemId())) {
            throw new ApiException(HttpStatus.CONFLICT, "판매 중인 물품만 수락할 수 있습니다.");
        }
        requests.markAccepted(id);
        requests.rejectOtherPending(request.itemId(), id);
        chats.openForRequest(id);
    }
}
