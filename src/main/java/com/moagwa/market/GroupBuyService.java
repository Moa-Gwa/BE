package com.moagwa.market;

import com.moagwa.chat.ChatService;
import com.moagwa.common.exception.ApiException;
import com.moagwa.market.GroupBuyRepository.DetailExtra;
import com.moagwa.market.GroupBuyRepository.LockedGroupBuy;
import com.moagwa.market.GroupBuyRepository.NewGroupBuy;
import com.moagwa.market.MarketDto.GroupBuyDetail;
import com.moagwa.market.MarketDto.GroupBuyPage;
import com.moagwa.market.MarketDto.GroupBuyRequest;
import com.moagwa.market.MarketDto.GroupBuySummary;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 같이 사기(공동구매). 목표 수량을 정해 모집하고, 마감 전까지 참여·취소할 수 있다. */
@Service
@RequiredArgsConstructor
class GroupBuyService {

    private static final int MY_LIST_LIMIT = 100;

    private final GroupBuyRepository groupBuys;
    private final ChatService chats;

    @Transactional(readOnly = true)
    GroupBuyPage search(String status, String query, int page, int size, long me) {
        boolean recruiting = "RECRUITING".equals(status);
        if (!recruiting && !"CLOSED".equals(status)) {
            throw ApiException.badRequest("올바르지 않은 모집 상태입니다.");
        }
        PageWindow window = PageWindow.of(page, size);
        List<GroupBuySummary> fetched = recruiting
                ? groupBuys.findRecruiting(query, me, window)
                : groupBuys.findClosed(query, me, window);
        return new GroupBuyPage(window.content(fetched), window.page(), window.size(), window.hasNext(fetched));
    }

    @Transactional
    GroupBuyDetail create(GroupBuyRequest body, long me) {
        if (!body.deadlineAt().isAfter(Instant.now())) {
            throw ApiException.badRequest("마감 시각은 지금보다 뒤여야 합니다.");
        }
        MarketInput.requireUploadedImages(body.imageUrl() == null ? null : List.of(body.imageUrl()));
        long id = groupBuys.insert(me, new NewGroupBuy(body.title().trim(),
                MarketInput.trimToEmpty(body.description()), body.unitPrice(), body.targetQuantity(),
                body.deadlineAt(), MarketInput.trimToNull(body.pickupPlace()), body.imageUrl()));
        chats.openForGroupBuy(id);
        return detail(id, me);
    }

    @Transactional(readOnly = true)
    GroupBuyDetail find(long id, long me) {
        return detail(id, me);
    }

    /** 이미 참여했다면 수량을 바꾼다. 목표 수량을 넘겨서는 참여할 수 없다. */
    @Transactional
    GroupBuyDetail join(long id, int quantity, long me) {
        LockedGroupBuy groupBuy = groupBuys.lock(id).orElseThrow(GroupBuyService::notFound);
        boolean open = "RECRUITING".equals(groupBuy.status()) && groupBuy.deadlineAt().isAfter(Instant.now());
        if (!open) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 마감된 모집입니다.");
        }
        if (groupBuys.sumQuantityOfOthers(id, me) + quantity > groupBuy.targetQuantity()) {
            throw new ApiException(HttpStatus.CONFLICT, "남은 수량이 부족합니다.");
        }
        groupBuys.saveParticipant(id, me, quantity);
        return detail(id, me);
    }

    @Transactional
    void leave(long id, long me) {
        if (!groupBuys.isRecruiting(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "마감된 모집은 참여를 취소할 수 없습니다.");
        }
        groupBuys.deleteParticipant(id, me);
    }

    @Transactional
    void close(long id, long me) {
        long hostId = groupBuys.findHostId(id).orElseThrow(GroupBuyService::notFound);
        if (hostId != me) {
            throw ApiException.forbidden("모집을 연 사람만 마감할 수 있습니다.");
        }
        groupBuys.close(id);
    }

    @Transactional(readOnly = true)
    List<GroupBuySummary> findMine(long me) {
        return groupBuys.findByMember(me, MY_LIST_LIMIT);
    }

    private GroupBuyDetail detail(long id, long me) {
        GroupBuySummary summary = groupBuys.findSummary(id, me).orElseThrow(GroupBuyService::notFound);
        DetailExtra extra = groupBuys.findDetailExtra(id).orElseThrow(GroupBuyService::notFound);
        return new GroupBuyDetail(summary, extra.description(), extra.hostId() == me, extra.participants());
    }

    private static ApiException notFound() {
        return ApiException.notFound("모집 글을 찾을 수 없습니다.");
    }
}
