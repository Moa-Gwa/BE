package com.moagwa.chat;

import com.moagwa.chat.ChatDto.Event;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 저장이 커밋된 뒤 방 멤버 각자의 /user/queue/chat 으로 보낸다. 롤백된 메시지는 나가지 않는다.
 * 단일 서버 기준이다. 서버를 여러 대로 늘리면 외부 브로커(Redis 등)로 바꿔야 한다(BE #8).
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

    private void send(List<Long> members, Event event) {
        members.forEach(id -> messaging.convertAndSendToUser(String.valueOf(id), QUEUE, event));
    }
}
