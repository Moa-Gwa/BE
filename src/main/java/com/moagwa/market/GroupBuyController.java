package com.moagwa.market;

import com.moagwa.auth.CurrentUser;
import com.moagwa.market.MarketDto.GroupBuyDetail;
import com.moagwa.market.MarketDto.GroupBuyPage;
import com.moagwa.market.MarketDto.GroupBuyRequest;
import com.moagwa.market.MarketDto.JoinRequest;
import com.moagwa.market.MarketDto.ParticipantView;
import com.moagwa.market.MarketDto.PaymentConfirmRequest;
import com.moagwa.market.MarketDto.PaymentOrder;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "같이 사기", description = "재학생 인증 필요")
@RestController
@RequestMapping("/api/market/group-buys")
@RequiredArgsConstructor
class GroupBuyController {

    private final GroupBuyService groupBuys;
    private final GroupBuyPaymentService payments;

    @GetMapping
    GroupBuyPage list(@RequestParam(defaultValue = "RECRUITING") String status,
                      @RequestParam(required = false) String q,
                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                      Authentication auth) {
        return groupBuys.search(status, q, page, size, me(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GroupBuyDetail create(@Valid @RequestBody GroupBuyRequest body, Authentication auth) {
        return groupBuys.create(body, me(auth));
    }

    @GetMapping("/{id}")
    GroupBuyDetail get(@PathVariable long id, Authentication auth) {
        return groupBuys.find(id, me(auth));
    }

    @PostMapping("/{id}/join")
    @ResponseStatus(HttpStatus.CREATED)
    GroupBuyDetail join(@PathVariable long id, @Valid @RequestBody JoinRequest body, Authentication auth) {
        return groupBuys.join(id, body.quantity(), me(auth));
    }

    @DeleteMapping("/{id}/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(@PathVariable long id, Authentication auth) {
        groupBuys.leave(id, me(auth));
    }

    @PostMapping("/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(@PathVariable long id, Authentication auth) {
        groupBuys.close(id, me(auth));
    }

    /** 결제 주문 생성. 응답의 orderId·amount로 결제위젯 결제 요청 */
    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentOrder order(@PathVariable long id, @Valid @RequestBody JoinRequest body, Authentication auth) {
        return payments.createOrder(id, body.quantity(), me(auth));
    }

    /** 결제위젯 성공 주소로 받은 값으로 승인. 승인되면 참여 확정 */
    @PostMapping("/payments/confirm")
    GroupBuyDetail confirm(@Valid @RequestBody PaymentConfirmRequest body, Authentication auth) {
        long id = payments.confirm(body.paymentKey(), body.orderId(), body.amount(), me(auth));
        return groupBuys.find(id, me(auth));
    }

    /** 주최자의 모집 취소. 결제한 참여자 전원 환불 */
    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable long id, Authentication auth) {
        payments.cancelGroupBuy(id, me(auth));
    }

    @GetMapping("/{id}/participants")
    List<ParticipantView> participants(@PathVariable long id, Authentication auth) {
        return payments.participants(id, me(auth));
    }

    private static long me(Authentication auth) {
        return CurrentUser.from(auth).id();
    }
}
