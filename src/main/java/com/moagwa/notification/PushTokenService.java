package com.moagwa.notification;

import com.moagwa.common.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class PushTokenService {
    private static final int USER_AGENT_MAX = 300;

    private final PushTokenRepository tokens;
    private final PushSender pushSender;

    void register(long user, String token, String userAgent) {
        tokens.upsert(user, token, userAgent == null ? null : userAgent.substring(0, Math.min(USER_AGENT_MAX, userAgent.length())));
    }

    /** 본인 토큰만 삭제. 없는 토큰은 오류 아님 */
    void unregister(long user, String token) {
        tokens.delete(user, token);
    }

    /** 이 기기로 테스트 알림 발송. 내 계정에 등록된 토큰만 가능 */
    void sendTest(long user, String token) {
        if (!tokens.isRegistered(user, token)) {
            throw ApiException.notFound("이 기기는 알림 받을 기기로 등록되어 있지 않습니다. 알림을 다시 켜 주세요.");
        }
        if (!tokens.claimTestAttempt(user)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "테스트 알림은 1분에 한 번만 보낼 수 있습니다.");
        }
        pushSender.sendTest(token);
    }
}
