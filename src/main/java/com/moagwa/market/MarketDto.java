package com.moagwa.market;

import java.util.List;

/** 생활거래 API의 응답 모양. */
final class MarketDto {

    private MarketDto() {
    }

    record Seller(long id, String nickname, String department) {
    }

    record ItemSummary(long id, String title, int price, String category, String status, String tradePlace,
                       String thumbnail, int likeCount, boolean liked, String createdAt) {
    }

    record ItemDetail(long id, String title, String description, int price, String category, String status,
                      String tradePlace, List<String> imageUrls, int likeCount, int viewCount, boolean liked,
                      boolean mine, Seller seller, String createdAt) {
    }

    record ItemPage(List<ItemSummary> items, int page, int size, boolean hasNext) {
    }

    record RequestView(long id, long itemId, String itemTitle, long buyerId, String buyerNickname, String message,
                       String status, String createdAt) {
    }

    record GroupBuySummary(long id, String title, int unitPrice, int targetQuantity, int joinedQuantity,
                           String deadlineAt, String pickupPlace, String imageUrl, String status, String hostNickname,
                           int myQuantity, String createdAt) {
    }

    record GroupBuyDetail(GroupBuySummary summary, String description, boolean mine, int participants) {
    }

    record GroupBuyPage(List<GroupBuySummary> items, int page, int size, boolean hasNext) {
    }
}
