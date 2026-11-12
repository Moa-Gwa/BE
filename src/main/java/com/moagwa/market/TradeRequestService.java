package com.moagwa.market;

import com.moagwa.chat.ChatService;
import com.moagwa.common.exception.ApiException;
import com.moagwa.market.MarketDto.RequestCreated;
import com.moagwa.market.MarketDto.RequestView;
import com.moagwa.market.MarketItemRepository.SaleState;
import com.moagwa.market.TradeRequestRepository.LockedRequest;
import com.moagwa.market.TradeRequestRepository.Parties;
import com.moagwa.notification.NotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매자의 거래 요청·취소와 판매자의 수락·거절.
 * 물품 상태와 요청 상태를 함께 바꾸는 흐름은 모두 물품 → 요청 순서로 잠가 동시 처리 시 교착과 상태 불일치를 막음
 */
@Service
@RequiredArgsConstructor
class TradeRequestService {

    private static final int MY_LIST_LIMIT = 100;

    private final TradeRequestRepository requests;
    private final MarketItemRepository items;
    private final MarketItemService itemService;
    private final ChatService chats;
    private final NotificationService notifications;

    @Transactional
    RequestCreated create(long itemId, String message, long me) {
        SaleState item = items.lockSaleState(itemId)
                .orElseThrow(() -> ApiException.notFound("물품을 찾을 수 없습니다."));
        if (item.sellerId() == me) {
            throw ApiException.badRequest("내 물품에는 거래를 요청할 수 없습니다.");
        }
        if (!"ON_SALE".equals(item.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "판매 중인 물품에만 거래를 요청할 수 있습니다.");
        }
        long id = requests.insert(itemId, me, message == null ? null : message.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "이미 거래를 요청했습니다."));
        Parties p = requests.findParties(id);
        notifications.notifyTrade(p.sellerId(), "TRADE_REQUESTED", p.itemId(), p.itemTitle(),
                p.buyerNickname() + "님이 거래를 요청했어요.");
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
        LockedRequest request = lockWithItem(id);
        itemService.requireSeller(request.itemId(), me);
        if (!"PENDING".equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 처리된 요청입니다.");
        }
        if (!items.reserveIfOnSale(request.itemId())) {
            throw new ApiException(HttpStatus.CONFLICT, "판매 중인 물품만 수락할 수 있습니다.");
        }
        requests.updateStatus(id, "ACCEPTED");
        requests.rejectOtherPending(request.itemId(), id);
        chats.openForRequest(id);
        Parties p = requests.findParties(id);
        notifications.notifyTrade(p.buyerId(), "TRADE_ACCEPTED", p.itemId(), p.itemTitle(),
                "판매자가 거래 요청을 수락했어요. 채팅에서 약속을 정해 보세요.");
    }

    /** 판매자가 대기 중인 요청을 거절한다. 거절된 구매자는 다시 요청할 수 있다. */
    @Transactional
    void reject(long id, long me) {
        LockedRequest request = requests.lock(id)
                .orElseThrow(() -> ApiException.notFound("거래 요청을 찾을 수 없습니다."));
        itemService.requireSeller(request.itemId(), me);
        if (!"PENDING".equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "대기 중인 요청만 거절할 수 있습니다.");
        }
        requests.updateStatus(id, "REJECTED");
    }

    /** 구매자가 자기 요청을 취소한다. 수락된 요청을 취소하면 예약이 풀려 물품이 다시 판매 중이 된다. */
    @Transactional
    void cancel(long id, long me) {
        LockedRequest request = lockWithItem(id);
        if (request.buyerId() != me) {
            throw ApiException.notFound("거래 요청을 찾을 수 없습니다.");
        }
        switch (request.status()) {
            case "PENDING" -> requests.updateStatus(id, "CANCELED");
            case "ACCEPTED" -> {
                if (items.findSaleState(request.itemId()).map(i -> "SOLD".equals(i.status())).orElse(false)) {
                    throw new ApiException(HttpStatus.CONFLICT, "거래가 끝난 요청은 취소할 수 없습니다.");
                }
                requests.updateStatus(id, "CANCELED");
                items.releaseIfReserved(request.itemId());
            }
            default -> throw new ApiException(HttpStatus.CONFLICT, "이미 거절되었거나 취소된 요청입니다.");
        }
    }

    private LockedRequest lockWithItem(long id) {
        long itemId = requests.findItemId(id)
                .orElseThrow(() -> ApiException.notFound("거래 요청을 찾을 수 없습니다."));
        items.lockSaleState(itemId);
        return requests.lock(id).orElseThrow(() -> ApiException.notFound("거래 요청을 찾을 수 없습니다."));
    }
}
