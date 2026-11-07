package com.moagwa.chat;

import com.moagwa.auth.CurrentUser;
import com.moagwa.chat.ChatDto.MessagePage;
import com.moagwa.chat.ChatDto.ReadRequest;
import com.moagwa.chat.ChatDto.RoomSummary;
import com.moagwa.chat.ChatDto.SendRequest;
import com.moagwa.common.exception.ApiException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 조회·읽음 처리는 REST, 전송은 STOMP(/app/chat.send). 새 메시지·읽음은 /user/queue/chat 으로 수신 */
@RestController
@RequiredArgsConstructor
class ChatController {

    private final ChatService chats;

    @GetMapping("/api/me/chats")
    List<RoomSummary> rooms(Authentication auth) {
        return chats.rooms(CurrentUser.from(auth).id());
    }

    @GetMapping("/api/chats/{roomId}/messages")
    MessagePage messages(@PathVariable long roomId, @RequestParam(required = false) Long before,
                         Authentication auth) {
        return chats.messages(roomId, before, CurrentUser.from(auth).id());
    }

    @PutMapping("/api/chats/{roomId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@PathVariable long roomId, @RequestBody ReadRequest body, Authentication auth) {
        chats.read(roomId, body.lastReadId(), CurrentUser.from(auth).id());
    }

    /** /ws 핸드셰이크에서 쿠키로 인증됨. principal 이름 = 사용자 id */
    @MessageMapping("/chat.send")
    void send(SendRequest body, Principal principal) {
        chats.send(body.roomId(), body.content(), Long.parseLong(principal.getName()));
    }

    /** 전송 실패는 보낸 사람의 /user/queue/errors 로만 전달 */
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    Map<String, String> sendFailed(ApiException e) {
        return Map.of("message", e.getMessage());
    }
}
