package com.moagwa.market;

import com.moagwa.common.exception.ApiException;
import com.moagwa.market.MarketDto.ItemDetail;
import com.moagwa.market.MarketDto.ItemPage;
import com.moagwa.market.MarketDto.ItemRequest;
import com.moagwa.market.MarketDto.ItemSummary;
import com.moagwa.market.MarketItemRepository.ItemFields;
import com.moagwa.market.MarketItemRepository.ItemSearch;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 중고거래 물품. 판매자는 항상 토큰의 사용자이고, 수정과 삭제는 판매자만 할 수 있다. */
@Service
@RequiredArgsConstructor
class MarketItemService {

    private static final Set<String> CATEGORIES = Set.of("BOOK", "ELECTRONICS", "LIVING", "CLOTHES", "ETC");
    private static final Set<String> STATUSES = Set.of("ON_SALE", "RESERVED", "SOLD");
    private static final int MY_LIST_LIMIT = 100;

    private final MarketItemRepository items;

    @Transactional(readOnly = true)
    ItemPage search(String category, String status, String query, String sort, int page, int size, long me) {
        String categoryFilter = blankToNull(category);
        String statusFilter = blankToNull(status);
        if (categoryFilter != null) {
            requireIn(CATEGORIES, categoryFilter, "카테고리");
        }
        if (statusFilter != null) {
            requireIn(STATUSES, statusFilter, "판매 상태");
        }
        PageWindow window = PageWindow.of(page, size);
        List<ItemSummary> fetched = items.search(
                new ItemSearch(categoryFilter, statusFilter, blankToNull(query), sort), me, window);
        return new ItemPage(window.content(fetched), window.page(), window.size(), window.hasNext(fetched));
    }

    @Transactional
    ItemDetail create(ItemRequest body, long me) {
        validate(body);
        long id = items.insert(me, toFields(body));
        items.addImages(id, body.imageUrls());
        return detail(id, me);
    }

    @Transactional
    ItemDetail view(long id, long me) {
        items.increaseViewCount(id, me);
        return detail(id, me);
    }

    @Transactional
    ItemDetail update(long id, ItemRequest body, long me) {
        requireSeller(id, me);
        validate(body);
        items.update(id, toFields(body));
        items.deleteImages(id);
        items.addImages(id, body.imageUrls());
        return detail(id, me);
    }

    @Transactional
    void delete(long id, long me) {
        requireSeller(id, me);
        items.delete(id);
    }

    @Transactional
    ItemDetail changeStatus(long id, String status, long me) {
        requireSeller(id, me);
        requireIn(STATUSES, status, "판매 상태");
        items.updateStatus(id, status);
        return detail(id, me);
    }

    @Transactional
    void like(long id, long me) {
        if (items.findSellerId(id).isEmpty()) {
            throw notFound();
        }
        items.addLike(id, me);
    }

    @Transactional
    void unlike(long id, long me) {
        items.removeLike(id, me);
    }

    @Transactional(readOnly = true)
    List<ItemSummary> findMine(long me) {
        return items.findBySeller(me, MY_LIST_LIMIT);
    }

    @Transactional(readOnly = true)
    List<ItemSummary> findLiked(long me) {
        return items.findLikedBy(me, MY_LIST_LIMIT);
    }

    void requireSeller(long itemId, long me) {
        long sellerId = items.findSellerId(itemId).orElseThrow(MarketItemService::notFound);
        if (sellerId != me) {
            throw ApiException.forbidden("내가 올린 물품만 바꿀 수 있습니다.");
        }
    }

    private ItemDetail detail(long id, long me) {
        return items.findDetail(id, me).orElseThrow(MarketItemService::notFound);
    }

    private static void validate(ItemRequest body) {
        requireIn(CATEGORIES, body.category(), "카테고리");
        MarketInput.requireUploadedImages(body.imageUrls());
    }

    private static ItemFields toFields(ItemRequest body) {
        return new ItemFields(body.title().trim(), MarketInput.trimToEmpty(body.description()), body.price(),
                body.category(), MarketInput.trimToNull(body.tradePlace()));
    }

    private static void requireIn(Set<String> allowed, String value, String label) {
        if (!allowed.contains(value)) {
            throw ApiException.badRequest("올바르지 않은 " + label + "입니다.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ApiException notFound() {
        return ApiException.notFound("물품을 찾을 수 없습니다.");
    }
}
