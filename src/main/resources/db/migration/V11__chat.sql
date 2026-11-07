-- 거래·같이 사기 인앱 채팅.
-- 방은 수락된 거래 요청(1:1) 또는 같이 사기 모집(단체) 하나에 대응한다.
CREATE TABLE chat_rooms (
    id           BIGSERIAL PRIMARY KEY,
    request_id   BIGINT UNIQUE REFERENCES market_requests (id) ON DELETE CASCADE,
    group_buy_id BIGINT UNIQUE REFERENCES group_buys (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((request_id IS NULL) <> (group_buy_id IS NULL))
);

CREATE TABLE chat_messages (
    id         BIGSERIAL PRIMARY KEY,
    room_id    BIGINT        NOT NULL REFERENCES chat_rooms (id) ON DELETE CASCADE,
    sender_id  BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    content    VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_messages_room ON chat_messages (room_id, id DESC);

-- 각자 마지막으로 읽은 메시지. 안 읽은 수와 상대방의 읽음 표시를 여기서 계산한다.
CREATE TABLE chat_reads (
    room_id      BIGINT NOT NULL REFERENCES chat_rooms (id) ON DELETE CASCADE,
    user_id      BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    last_read_id BIGINT NOT NULL,
    PRIMARY KEY (room_id, user_id)
);

-- 방 멤버는 따로 저장하지 않고 거래·모집 상태에서 바로 계산한다(참여·취소와 어긋나지 않게).
-- 거래 방: 수락된 요청의 판매자와 구매자. 단체방: 모집을 연 사람과 현재 참여자.
CREATE VIEW chat_members AS
SELECT r.id AS room_id, i.seller_id AS user_id
  FROM chat_rooms r
  JOIN market_requests q ON q.id = r.request_id
  JOIN market_items i ON i.id = q.item_id
 WHERE q.status = 'ACCEPTED'
UNION
SELECT r.id, q.buyer_id
  FROM chat_rooms r
  JOIN market_requests q ON q.id = r.request_id
 WHERE q.status = 'ACCEPTED'
UNION
SELECT r.id, g.host_id
  FROM chat_rooms r
  JOIN group_buys g ON g.id = r.group_buy_id
UNION
SELECT r.id, p.user_id
  FROM chat_rooms r
  JOIN group_buy_participants p ON p.group_buy_id = r.group_buy_id;

-- 이미 수락된 거래와 열려 있는 모집에도 방을 만든다.
INSERT INTO chat_rooms (request_id) SELECT id FROM market_requests WHERE status = 'ACCEPTED';
INSERT INTO chat_rooms (group_buy_id) SELECT id FROM group_buys;
