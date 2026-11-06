package com.moagwa.market;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** 생활거래 API의 요청과 응답 모양. */
final class MarketDto {

    private static final int MAX_JOIN_QUANTITY = 20;

    private MarketDto() {
    }

    record ItemRequest(@NotBlank @Size(max = 60) String title, @Size(max = 2000) String description,
                       @NotNull @Min(0) @Max(100_000_000) Integer price, @NotBlank String category,
                       @Size(max = 100) String tradePlace, @Size(max = 10) List<String> imageUrls) {
    }

    record StatusRequest(@NotBlank String status) {
    }

    record TradeRequest(@Size(max = 200) String message) {
    }

    record GroupBuyRequest(@NotBlank @Size(max = 60) String title, @Size(max = 2000) String description,
                           @NotNull @Min(0) @Max(100_000_000) Integer unitPrice,
                           @NotNull @Min(2) @Max(1000) Integer targetQuantity, @NotNull Instant deadlineAt,
                           @Size(max = 100) String pickupPlace, @Size(max = 300) String imageUrl) {
    }

    record JoinRequest(@NotNull @Min(1) @Max(MAX_JOIN_QUANTITY) Integer quantity) {
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

    record RequestCreated(long id, String status) {
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
