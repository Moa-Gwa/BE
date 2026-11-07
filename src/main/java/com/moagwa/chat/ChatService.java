package com.moagwa.chat;

import com.moagwa.chat.ChatDto.Message;
import com.moagwa.chat.ChatDto.MessagePage;
import com.moagwa.chat.ChatDto.ReadState;
import com.moagwa.chat.ChatDto.RoomSummary;
import com.moagwa.common.exception.ApiException;
import com.moagwa.notification.PushRequested;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int MAX_LENGTH = 1000;
    private static final int PAGE_SIZE = 30;
    private static final int ROOM_LIMIT = 100;
    private static final int PUSH_PREVIEW = 100;
    private static final String CHAT_DESTINATION = "/user" + ChatRelay.QUEUE;

    private final ChatRepository chats;
    private final ApplicationEventPublisher events;
    private final SimpUserRegistry connectedUsers;

    /** 판매자가 거래 요청을 수락하면 판매자·구매자 1:1 방을 연다. */
    public void openForRequest(long requestId) {
        chats.createForRequest(requestId);
    }

    /** 같이 사기 모집을 열면 단체방을 연다. 참여자는 참여하는 순간 멤버가 된다. */
    public void openForGroupBuy(long groupBuyId) {
        chats.createForGroupBuy(groupBuyId);
    }

    @Transactional(readOnly = true)
    List<RoomSummary> rooms(long me) {
        return chats.findRooms(me, ROOM_LIMIT);
    }

    @Transactional(readOnly = true)
    MessagePage messages(long roomId, Long before, long me) {
        requireMember(roomId, me);
        List<Message> newestFirst = chats.findMessages(roomId, before, PAGE_SIZE + 1);
        boolean hasMore = newestFirst.size() > PAGE_SIZE;
        List<Message> page = new ArrayList<>(newestFirst.subList(0, Math.min(PAGE_SIZE, newestFirst.size())));
        Collections.reverse(page);
        return new MessagePage(page, hasMore, chats.reads(roomId));
    }

    @Transactional
    Message send(long roomId, String rawContent, long me) {
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.isEmpty() || content.length() > MAX_LENGTH) {
            throw ApiException.badRequest("메시지는 1~" + MAX_LENGTH + "자로 입력해 주세요.");
        }
        requireMember(roomId, me);
        Message message = chats.insert(roomId, me, content);
        chats.markRead(roomId, me, message.id());
        List<Long> members = chats.members(roomId);
        events.publishEvent(new ChatEvents.MessageSent(members, message));
        // 채팅 실시간 채널을 구독 중이지 않은 멤버에게만 푸시를 보낸다. 화면에서 이미 받은 사람에게 중복으로 가지 않게 한다.
        List<Long> offline = members.stream().filter(id -> id != me && !listening(id)).toList();
        if (!offline.isEmpty()) {
            events.publishEvent(new PushRequested(offline, message.senderName(), preview(content),
                    "/life/chats/" + roomId));
        }
        return message;
    }

    @Transactional
    void read(long roomId, long lastReadId, long me) {
        requireMember(roomId, me);
        chats.markRead(roomId, me, lastReadId).ifPresent(last -> events.publishEvent(
                new ChatEvents.ReadUpdated(chats.members(roomId), roomId, new ReadState(me, last))));
    }

    /** 연결만 있고 채팅 채널을 구독하지 않은 경우(오류 채널만 구독 등)에는 푸시를 받아야 한다. */
    private boolean listening(long userId) {
        SimpUser user = connectedUsers.getUser(String.valueOf(userId));
        return user != null && user.getSessions().stream().flatMap(s -> s.getSubscriptions().stream())
                .anyMatch(s -> CHAT_DESTINATION.equals(s.getDestination()));
    }

    private void requireMember(long roomId, long me) {
        if (!chats.isMember(roomId, me)) {
            throw ApiException.forbidden("채팅방에 참여한 사람만 볼 수 있습니다.");
        }
    }

    private static String preview(String content) {
        return content.length() <= PUSH_PREVIEW ? content : content.substring(0, PUSH_PREVIEW) + "…";
    }
}
