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
 * 같이 사기 참여 결제. 주문 생성 → 결제위젯 → 승인 시 참여 확정.
 * 참여 취소·모집 취소·목표 미달 시 전액 환불
 */
@Slf4j
@Service
class GroupBuyPaymentService {

    private static final int REFUND_BATCH = 50;

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

    /** 유료 모집이고 결제 기능이 켜져 있으면 결제 후 참여 */
    boolean required(int unitPrice) {
        return unitPrice > 0 && toss.enabled();
    }

    boolean paid(long groupBuyId, long userId) {
        return payments.findPaidOrderId(groupBuyId, userId).isPresent();
    }

    @Transactional
    PaymentOrder createOrder(long groupBuyId, int quantity, long me) {
        LockedGroupBuy g = groupBuys.lock(groupBuyId).orElseThrow(GroupBuyPaymentService::notFound);
        if (!required(g.unitPrice())) {
            throw new ApiException(HttpStatus.CONFLICT, "결제가 필요 없는 모집입니다.");
        }
        checkJoinable(g, groupBuyId, quantity, me);
        // 주문번호 규칙: 6~64자, 영문·숫자·-·_·=
        String orderId = "GB" + groupBuyId + "-" + UUID.randomUUID().toString().replace("-", "");
        int amount = Math.multiplyExact(g.unitPrice(), quantity);
        payments.insert(orderId, groupBuyId, me, quantity, amount);
        String name = g.title() + (quantity > 1 ? " " + quantity + "개" : "");
        return new PaymentOrder(orderId, amount, name.length() > 100 ? name.substring(0, 100) : name);
    }

    /** 결제위젯 성공 후 승인. 같은 주문을 다시 승인하면 결과만 반환 */
    @Transactional
    long confirm(String paymentKey, String orderId, long amount, long me) {
        Order order = payments.lock(orderId).orElseThrow(() -> ApiException.notFound("주문을 찾을 수 없습니다."));
        if (order.userId() != me) {
            throw ApiException.forbidden("본인 주문만 결제할 수 있습니다.");
        }
        if ("PAID".equals(order.status()) && paymentKey.equals(order.paymentKey())) {
            return order.groupBuyId();
        }
        if (!"READY".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 처리된 주문입니다.");
        }
        if (amount != order.amount()) {
            throw ApiException.badRequest("결제 금액이 주문 금액과 다릅니다.");
        }
        // 결제 승인 전에 다시 확인. 여기서 막히면 결제는 승인되지 않음
        LockedGroupBuy g = groupBuys.lock(order.groupBuyId()).orElseThrow(GroupBuyPaymentService::notFound);
        checkJoinable(g, order.groupBuyId(), order.quantity(), me);

        TossPaymentsClient.Payment paid = toss.confirm(paymentKey, orderId, amount);
        if (!"DONE".equals(paid.status()) || paid.totalAmount() != amount) {
            toss.cancel(paymentKey, orderId, "승인 결과 불일치로 자동 취소");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "결제 승인 결과를 확인하지 못해 결제를 취소했습니다.");
        }
        try {
            payments.markPaid(orderId, paymentKey);
            groupBuys.saveParticipant(order.groupBuyId(), me, order.quantity());
        } catch (RuntimeException e) {
            // shortcut: 커밋 단계 실패는 잡지 못함. 저장 쿼리 실패만 결제 취소로 되돌림
            toss.cancel(paymentKey, orderId, "결제 처리 중 오류로 자동 취소");
            throw e;
        }
        return order.groupBuyId();
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
        int canceled = payments.cancelUnmetGroupBuys();
        if (canceled > 0) {
            log.info("Canceled {} group buys that missed their target", canceled);
        }
        refundCanceled(0);
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
        if (payments.findPaidOrderId(groupBuyId, me).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 참여 중입니다. 수량을 바꾸려면 참여를 취소한 뒤 다시 신청해 주세요.");
        }
        if (groupBuys.sumQuantityOfOthers(groupBuyId, me) + quantity > g.targetQuantity()) {
            throw new ApiException(HttpStatus.CONFLICT, "남은 수량이 부족합니다.");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("모집 글을 찾을 수 없습니다.");
    }
}
