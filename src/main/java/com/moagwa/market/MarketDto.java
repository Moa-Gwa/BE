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

    /**
     * paymentRequired: 결제 후 참여(유료 모집이고 결제 기능이 켜진 경우)
     * myPaid: 내 참여가 결제 완료 상태인지. 결제 기능 도입 전 참여자는 false
     */
    record GroupBuyDetail(GroupBuySummary summary, String description, boolean mine, int participants,
                          boolean paymentRequired, boolean myPaid) {
    }

    /** 결제위젯에 넘길 주문. 금액은 서버가 단가 × 수량으로 계산 */
    record PaymentOrder(String orderId, int amount, String orderName) {
    }

    record PaymentConfirmRequest(@NotBlank @Size(max = 200) String paymentKey,
                                 @NotBlank @Size(max = 64) String orderId, @NotNull Long amount) {
    }

    /** 주최자 화면의 참여자별 결제 상태 */
    record ParticipantView(String nickname, int quantity, boolean paid, String joinedAt) {
    }

    record GroupBuyPage(List<GroupBuySummary> items, int page, int size, boolean hasNext) {
    }
}
