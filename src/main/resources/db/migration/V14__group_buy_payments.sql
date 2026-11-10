-- 같이 사기 참여 결제. 주문 1건 = 참여자 1명의 결제 1회
-- status: READY(주문 생성) → APPROVING(결제사 승인 요청 중) → PAID(승인 완료) → CANCELED(환불)
--         결제사 승인 거절 시 FAILED. 승인 후 참여를 확정하지 못한 주문은 CANCELING(취소 선점) 후 CANCELED
CREATE TABLE group_buy_payments (
    id            BIGSERIAL PRIMARY KEY,
    order_id      VARCHAR(64)  NOT NULL UNIQUE,
    group_buy_id  BIGINT       NOT NULL REFERENCES group_buys (id) ON DELETE CASCADE,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    quantity      INT          NOT NULL CHECK (quantity > 0),
    amount        INT          NOT NULL CHECK (amount > 0),
    status        VARCHAR(10)  NOT NULL DEFAULT 'READY',
    payment_key   VARCHAR(200) UNIQUE,
    approving_at  TIMESTAMPTZ,
    paid_at       TIMESTAMPTZ,
    canceled_at   TIMESTAMPTZ,
    cancel_reason VARCHAR(200),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_group_buy_payments_member ON group_buy_payments (group_buy_id, user_id);
-- 참여자 1명당 결제 완료 1건(중복 승인 방지)
CREATE UNIQUE INDEX uq_group_buy_payments_paid ON group_buy_payments (group_buy_id, user_id) WHERE status = 'PAID';
