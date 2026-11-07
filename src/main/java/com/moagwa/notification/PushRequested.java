package com.moagwa.notification;

import java.util.Collection;

/**
 * 받는 사람들에게 보낼 푸시 요청. 발행 트랜잭션 커밋 후 {@link PushSender}가 발송
 * url: 알림을 눌렀을 때 열 앱 안 경로(예: /notices/crawled/12, /life/chats/3)
 */
public record PushRequested(Collection<Long> userIds, String title, String body, String url) {}
