package com.moagwa.chat;

import com.moagwa.chat.ChatDto.Event;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 저장 커밋 후 방 멤버 각자의 /user/queue/chat 으로 전달. 롤백된 메시지는 미전달
 * 단일 서버 기준. 서버 여러 대로 확장 시 외부 브로커(Redis 등) 필요(BE #8)
 */
@Component
@RequiredArgsConstructor
class ChatRelay {

    static final String QUEUE = "/queue/chat";

    private final SimpMessagingTemplate messaging;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ChatEvents.MessageSent e) {
        send(e.members(), Event.message(e.message()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ChatEvents.ReadUpdated e) {
        send(e.members(), Event.read(e.roomId(), e.read()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ChatEvents.RoomOpened e) {
        send(e.members(), Event.room(e.roomId()));
    }

    private void send(List<Long> members, Event event) {
        members.forEach(id -> messaging.convertAndSendToUser(String.valueOf(id), QUEUE, event));
    }
}
