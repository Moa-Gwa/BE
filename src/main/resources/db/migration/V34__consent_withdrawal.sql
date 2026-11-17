-- #118의 V33 다음 migration. 문서별·버전별 동의 이력은 append-only로 보관한다.
CREATE TABLE user_consents (
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    document_type VARCHAR(20)  NOT NULL CHECK (document_type IN ('TERMS', 'PRIVACY')),
    version       VARCHAR(20)  NOT NULL,
    agreed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, document_type, version)
);

-- 결제 기록은 전자상거래법에 따라 탈퇴 후에도 5년 보관하므로 회원, 같이 사기가 지워져도 행은 남기고 연결만 끊음
ALTER TABLE group_buy_payments
    ALTER COLUMN user_id DROP NOT NULL,
    ALTER COLUMN group_buy_id DROP NOT NULL,
    DROP CONSTRAINT group_buy_payments_user_id_fkey,
    DROP CONSTRAINT group_buy_payments_group_buy_id_fkey,
    ADD CONSTRAINT group_buy_payments_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    ADD CONSTRAINT group_buy_payments_group_buy_id_fkey
        FOREIGN KEY (group_buy_id) REFERENCES group_buys (id) ON DELETE SET NULL;
