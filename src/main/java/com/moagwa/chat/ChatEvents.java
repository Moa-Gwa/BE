package com.moagwa.chat;

import com.moagwa.chat.ChatDto.Message;
import com.moagwa.chat.ChatDto.ReadState;
import java.util.List;

/** 커밋 후 {@link ChatRelay}가 멤버들에게 실시간으로 전달하는 이벤트. */
final class ChatEvents {

    private ChatEvents() {}

    record MessageSent(List<Long> members, Message message) {}

    record ReadUpdated(List<Long> members, long roomId, ReadState read) {}
}
