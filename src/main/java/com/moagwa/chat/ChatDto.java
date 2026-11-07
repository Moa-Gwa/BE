package com.moagwa.chat;

import java.util.List;

/** 채팅 API와 실시간(STOMP) 메시지 형식. */
final class ChatDto {

    private ChatDto() {}

    /** 채팅 목록 한 줄. type은 TRADE(중고거래 1:1) 또는 GROUP(같이 사기 단체방). */
    record RoomSummary(long id, String type, String title, String partner, Long itemId, Long groupBuyId,
                       String lastMessage, String lastAt, long unread) {}

    record Message(long id, long roomId, long senderId, String senderName, String content, String createdAt) {}

    /** 오래된 순으로 정렬한 메시지와, 멤버별 마지막으로 읽은 메시지(읽음 표시용). */
    record MessagePage(List<Message> messages, boolean hasMore, List<ReadState> reads) {}

    record ReadState(long userId, long lastReadId) {}

    record SendRequest(long roomId, String content) {}

    record ReadRequest(long lastReadId) {}

    /** /user/queue/chat 으로 가는 이벤트. type은 message 또는 read. */
    record Event(String type, long roomId, Message message, ReadState read) {

        static Event message(Message m) {
            return new Event("message", m.roomId(), m, null);
        }

        static Event read(long roomId, ReadState r) {
            return new Event("read", roomId, null, r);
        }
    }
}
