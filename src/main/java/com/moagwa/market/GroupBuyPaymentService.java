package com.moagwa.market;

import com.moagwa.common.exception.ApiException;
import com.moagwa.market.GroupBuyPaymentRepository.Order;
import com.moagwa.market.GroupBuyRepository.LockedGroupBuy;
import com.moagwa.market.MarketDto.ParticipantView;
import com.moagwa.market.MarketDto.PaymentOrder;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같이 사기 참가비 결제. 주문 생성 → 결제위젯 → 승인 시 참여 확정.
 * 참가비는 수량과 관계없이 1회 고정이고, 물품 대금은 주최자와 참여자가 직접 정산.
 * 참여 취소·모집 취소·목표 미달 시 참가비 전액 환불
 */
@Slf4j
@Service
class GroupBuyPaymentService {

    /** 참여 1건당 참가비(원) */
    static final int PARTICIPATION_FEE = 500;
    private static final int REFUND_BATCH = 50;
    /** 승인 요청 후 이 시간이 지나도 승인 중이면 저장 실패로 보고 정리 */
    private static final int STALE_APPROVING_MINUTES = 10;

    private final GroupBuyPaymentRepository payments;
    private final GroupBuyRepository groupBuys;
    private final TossPaymentsClient toss;
    private final TransactionTemplate tx;

    GroupBuyPaymentService(GroupBuyPaymentRepository payments, GroupBuyRepository groupBuys, TossPaymentsClient toss,
                           PlatformTransactionManager transactions) {
        this.payments = payments;
        this.groupBuys = groupBuys;
        this.toss = toss;
        this.tx = new TransactionTemplate(transactions);
    }

    /** 결제 기능이 켜져 있으면 모든 모집은 참가비 결제 후 참여 */
    boolean required() {
        return toss.enabled();
    }

    /** 내 결제 완료 금액(환불될 금액). 결제 완료 주문이 없으면 0 */
    int paidAmount(long groupBuyId, long userId) {
        return payments.findPaidAmount(groupBuyId, userId).orElse(0);
    }

    @Transactional
    PaymentOrder createOrder(long groupBuyId, int quantity, long me) {
        LockedGroupBuy g = groupBuys.lock(groupBuyId).orElseThrow(GroupBuyPaymentService::notFound);
        if (!required()) {
            throw new ApiException(HttpStatus.CONFLICT, "결제가 필요 없는 모집입니다.");
        }
        checkJoinable(g, groupBuyId, quantity, me);
        // 주문번호 규칙: 6~64자, 영문·숫자·-·_·=
        String orderId = "GB" + groupBuyId + "-" + UUID.randomUUID().toString().replace("-", "");
        payments.insert(orderId, groupBuyId, me, quantity, PARTICIPATION_FEE);
        String name = "같이 사기 참가비 · " + g.title();
        return new PaymentOrder(orderId, PARTICIPATION_FEE, name.length() > 100 ? name.substring(0, 100) : name);
    }

    /**
     * 결제위젯 성공 후 승인. 같은 주문을 다시 승인하면 결과만 반환.
     * 승인 중 상태를 먼저 커밋해, 결제사 승인 뒤 저장이 실패해도 정리 작업이 결제를 취소할 수 있게 함
     */
    long confirm(String paymentKey, String orderId, long amount, long me) {
        Order order = tx.execute(s -> reserve(paymentKey, orderId, amount, me));
        if ("PAID".equals(order.status())) {
            return order.groupBuyId();
        }
        TossPaymentsClient.Payment approved;
        try {
            approved = toss.confirm(paymentKey, orderId, amount);
        } catch (ApiException e) {
            // 카드 거절 등 확정된 실패만 FAILED. 결제사 응답 불명은 승인 중으로 두고 정리 작업이 확정
            if (e.status() == HttpStatus.BAD_REQUEST) {
                tx.executeWithoutResult(s -> payments.markFailed(orderId));
            }
            throw e;
        }
        if (!"DONE".equals(approved.status()) || approved.totalAmount() != amount) {
            cancelApproved(orderId, paymentKey, "승인 결과 불일치로 자동 취소");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "결제 승인 결과를 확인하지 못해 결제를 취소했습니다.");
        }
        try {
            tx.executeWithoutResult(s -> {
                // 승인을 기다리는 사이 모집이 취소됐으면 참여 확정 대신 결제 취소
                LockedGroupBuy g = groupBuys.lock(order.groupBuyId()).orElseThrow(GroupBuyPaymentService::notFound);
                if ("CANCELED".equals(g.status())) {
                    throw new ApiException(HttpStatus.CONFLICT, "모집이 취소되어 결제를 취소했습니다.");
                }
                if (payments.markPaid(orderId) == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "결제 처리 시간이 초과되어 결제를 취소했습니다.");
                }
                groupBuys.saveParticipant(order.groupBuyId(), me, order.quantity());
            });
        } catch (RuntimeException e) {
            cancelApproved(orderId, paymentKey, "결제 처리 중 오류로 자동 취소");
            throw e;
        }
        return order.groupBuyId();
    }

    /** 승인 전 확인 후 승인 중으로 표시. 여기서 막히면 결제는 승인되지 않음 */
    private Order reserve(String paymentKey, String orderId, long amount, long me) {
        Order order = payments.lock(orderId).orElseThrow(() -> ApiException.notFound("주문을 찾을 수 없습니다."));
        if (order.userId() != me) {
            throw ApiException.forbidden("본인 주문만 결제할 수 있습니다.");
        }
        if ("PAID".equals(order.status()) && paymentKey.equals(order.paymentKey())) {
            return order;
        }
        if ("APPROVING".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "결제를 처리하고 있습니다. 잠시 후 다시 확인해 주세요.");
        }
        if (!"READY".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 처리된 주문입니다.");
        }
        if (amount != order.amount()) {
            throw ApiException.badRequest("결제 금액이 주문 금액과 다릅니다.");
        }
        LockedGroupBuy g = groupBuys.lock(order.groupBuyId()).orElseThrow(GroupBuyPaymentService::notFound);
        checkJoinable(g, order.groupBuyId(), order.quantity(), me);
        payments.markApproving(orderId, paymentKey);
        return order;
    }

    /** 승인됐지만 참여를 확정하지 못한 결제 취소. 실패하면 정리 작업이 다시 시도 */
    private void cancelApproved(String orderId, String paymentKey, String reason) {
        try {
            if (!Boolean.TRUE.equals(tx.execute(s -> payments.claimCancel(orderId)))) {
                return;
            }
            toss.cancel(paymentKey, orderId, reason);
            tx.executeWithoutResult(s -> payments.markApprovalCanceled(orderId, reason));
        } catch (RuntimeException e) {
            log.warn("Cancel after approval failed, will retry: orderId={}", orderId, e);
        }
    }

    /** 참여 취소 시 결제 완료 주문이 있으면 전액 환불. 환불 실패 시 예외로 참여 취소도 막음 */
    void refundMember(long groupBuyId, long userId) {
        payments.findPaidOrderId(groupBuyId, userId).ifPresent(orderId -> refund(orderId, "참여 취소"));
    }

    /** 주최자의 모집 취소. 결제한 참여자 전원 환불, 실패분은 스케줄러가 재시도 */
    void cancelGroupBuy(long groupBuyId, long me) {
        tx.executeWithoutResult(s -> {
            LockedGroupBuy g = groupBuys.lock(groupBuyId).orElseThrow(GroupBuyPaymentService::notFound);
            if (g.hostId() != me) {
                throw ApiException.forbidden("모집을 연 사람만 취소할 수 있습니다.");
            }
            if (!g.open()) {
                throw new ApiException(HttpStatus.CONFLICT, "모집 중일 때만 취소할 수 있습니다.");
            }
            groupBuys.cancel(groupBuyId);
        });
        refundCanceled(groupBuyId);
    }

    @Transactional(readOnly = true)
    List<ParticipantView> participants(long groupBuyId, long me) {
        long hostId = groupBuys.findHostId(groupBuyId).orElseThrow(GroupBuyPaymentService::notFound);
        if (hostId != me) {
            throw ApiException.forbidden("모집을 연 사람만 참여자를 볼 수 있습니다.");
        }
        return payments.findParticipants(groupBuyId);
    }

    /** 목표 미달로 마감된 유료 모집을 취소하고, 취소된 모집의 결제를 환불 */
    @Scheduled(fixedDelayString = "${moagwa.payment.refund-interval:PT1M}",
            initialDelayString = "${moagwa.payment.refund-initial-delay:PT1M}")
    void refundFailedGroupBuys() {
        if (!toss.enabled()) {
            return;
        }
        resolveStaleApprovals();
        int canceled = payments.cancelUnmetGroupBuys();
        if (canceled > 0) {
            log.info("Canceled {} group buys that missed their target", canceled);
        }
        refundCanceled(0);
    }

    /** 승인 중으로 오래 남은 주문은 결제 취소로 확정. 결제사에 승인 기록이 없으면 실패로 정리 */
    private void resolveStaleApprovals() {
        for (Order order : payments.findStaleApproving(STALE_APPROVING_MINUTES, REFUND_BATCH)) {
            try {
                // 먼저 취소를 선점해, 늦게 끝난 승인 처리가 이 주문을 결제 완료로 바꾸지 못하게 함
                if (!Boolean.TRUE.equals(tx.execute(s -> payments.claimCancel(order.orderId())))) {
                    continue;
                }
                toss.cancel(order.paymentKey(), order.orderId(), "승인 처리 실패로 자동 취소");
                tx.executeWithoutResult(s -> payments.markApprovalCanceled(order.orderId(), "승인 처리 실패로 자동 취소"));
            } catch (ApiException e) {
                if (e.status() == HttpStatus.BAD_REQUEST) {
                    tx.executeWithoutResult(s -> payments.markFailed(order.orderId()));
                } else {
                    log.warn("Stale approval cleanup failed, will retry: orderId={}", order.orderId(), e);
                }
            }
        }
    }

    private void refundCanceled(long groupBuyId) {
        for (String orderId : payments.findRefundable(groupBuyId, REFUND_BATCH)) {
            try {
                tx.executeWithoutResult(s -> refund(orderId, "모집 취소"));
            } catch (RuntimeException e) {
                log.warn("Refund failed, will retry: orderId={}", orderId, e);
            }
        }
    }

    /** 호출하는 쪽의 트랜잭션 안에서 실행 */
    private void refund(String orderId, String reason) {
        Order order = payments.lock(orderId).orElseThrow();
        if (!"PAID".equals(order.status())) {
            return;
        }
        toss.cancel(order.paymentKey(), orderId, reason);
        payments.markCanceled(orderId, reason);
        groupBuys.deleteParticipant(order.groupBuyId(), order.userId());
    }

    private void checkJoinable(LockedGroupBuy g, long groupBuyId, int quantity, long me) {
        if (!g.open()) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 마감된 모집입니다.");
        }
        if (payments.hasActiveOrder(groupBuyId, me)) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 참여 중입니다. 수량을 바꾸려면 참여를 취소한 뒤 다시 신청해 주세요.");
        }
        int taken = groupBuys.sumQuantityOfOthers(groupBuyId, me) + payments.sumApprovingOfOthers(groupBuyId, me);
        if (taken + quantity > g.targetQuantity()) {
            throw new ApiException(HttpStatus.CONFLICT, "남은 수량이 부족합니다.");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("모집 글을 찾을 수 없습니다.");
    }
}
