-- 재학생 인증과 생활거래(중고거래 · 같이 사기).

ALTER TABLE users ADD COLUMN verified_at TIMESTAMPTZ;

-- 학교 메일 인증. 한 번 인증된 학교 메일은 한 계정에만 연결된다.
CREATE TABLE student_verifications (
    user_id         BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    school_email    VARCHAR(200) NOT NULL,
    code_hash       VARCHAR(100),
    code_expires_at TIMESTAMPTZ,
    attempts        INT          NOT NULL DEFAULT 0,
    last_sent_at    TIMESTAMPTZ,
    verified_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_student_verifications_email
    ON student_verifications (lower(school_email)) WHERE verified_at IS NOT NULL;

-- 중고거래 물품
CREATE TABLE market_items (
    id          BIGSERIAL PRIMARY KEY,
    seller_id   BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title       VARCHAR(60)  NOT NULL,
    description TEXT         NOT NULL DEFAULT '',
    price       INT          NOT NULL CHECK (price >= 0),
    category    VARCHAR(20)  NOT NULL,
    trade_place VARCHAR(100),
    status      VARCHAR(12)  NOT NULL DEFAULT 'ON_SALE',
    like_count  INT          NOT NULL DEFAULT 0,
    view_count  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_market_items_list ON market_items (created_at DESC, id DESC);
CREATE INDEX idx_market_items_seller ON market_items (seller_id);

CREATE TABLE market_item_images (
    id         BIGSERIAL PRIMARY KEY,
    item_id    BIGINT       NOT NULL REFERENCES market_items (id) ON DELETE CASCADE,
    url        VARCHAR(300) NOT NULL,
    sort_order SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX idx_market_item_images_item ON market_item_images (item_id, sort_order);

CREATE TABLE market_likes (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    item_id    BIGINT      NOT NULL REFERENCES market_items (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, item_id)
);

-- 거래 요청: 구매 희망자가 판매자에게 보낸다. 판매자가 수락하면 물품이 예약 중이 된다.
CREATE TABLE market_requests (
    id         BIGSERIAL PRIMARY KEY,
    item_id    BIGINT      NOT NULL REFERENCES market_items (id) ON DELETE CASCADE,
    buyer_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    message    VARCHAR(200),
    status     VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (item_id, buyer_id)
);
CREATE INDEX idx_market_requests_buyer ON market_requests (buyer_id, created_at DESC);

-- 같이 사기(공동구매)
CREATE TABLE group_buys (
    id              BIGSERIAL PRIMARY KEY,
    host_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title           VARCHAR(60)  NOT NULL,
    description     TEXT         NOT NULL DEFAULT '',
    unit_price      INT          NOT NULL CHECK (unit_price >= 0),
    target_quantity INT          NOT NULL CHECK (target_quantity >= 2),
    deadline_at     TIMESTAMPTZ  NOT NULL,
    pickup_place    VARCHAR(100),
    image_url       VARCHAR(300),
    status          VARCHAR(12)  NOT NULL DEFAULT 'RECRUITING',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_group_buys_list ON group_buys (status, deadline_at);

CREATE TABLE group_buy_participants (
    group_buy_id BIGINT      NOT NULL REFERENCES group_buys (id) ON DELETE CASCADE,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    quantity     INT         NOT NULL CHECK (quantity > 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_buy_id, user_id)
);
