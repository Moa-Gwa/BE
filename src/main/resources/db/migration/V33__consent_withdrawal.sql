-- 이용약관, 개인정보 수집/이용 동의. 동의한 문서 버전과 시각
ALTER TABLE users ADD COLUMN terms_version VARCHAR(20), ADD COLUMN terms_agreed_at TIMESTAMPTZ;

-- 결제 기록은 전자상거래법에 따라 탈퇴 후에도 5년 보관하므로 회원, 같이 사기가 지워져도 행은 남기고 연결만 끊음
ALTER TABLE group_buy_payments
    ALTER COLUMN user_id DROP NOT NULL,
    ALTER COLUMN group_buy_id DROP NOT NULL,
    DROP CONSTRAINT group_buy_payments_user_id_fkey,
    DROP CONSTRAINT group_buy_payments_group_buy_id_fkey,
    ADD CONSTRAINT group_buy_payments_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    ADD CONSTRAINT group_buy_payments_group_buy_id_fkey
        FOREIGN KEY (group_buy_id) REFERENCES group_buys (id) ON DELETE SET NULL;
