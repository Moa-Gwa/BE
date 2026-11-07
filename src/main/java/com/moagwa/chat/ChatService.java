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

    /** 거래 요청 수락 시 판매자·구매자 1:1 방 생성, 열린 화면에 목록 갱신 알림 */
    public void openForRequest(long requestId) {
        chats.createForRequest(requestId).ifPresent(roomId ->
                events.publishEvent(new ChatEvents.RoomOpened(chats.members(roomId), roomId)));
    }

    /** 같이 사기 모집 생성 시 단체방 생성. 참여자는 참여 즉시 멤버 */
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
        // 채팅 채널 미구독 멤버에게만 푸시. 화면에서 받은 사람에게 중복 발송 방지
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

    /** 채팅 채널 구독 여부. 연결만 있고 미구독(오류 채널만 구독 등)이면 푸시 대상 */
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
