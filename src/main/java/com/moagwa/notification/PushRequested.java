package com.moagwa.notification;

import java.util.Collection;

/**
 * 받는 사람들에게 푸시를 보내 달라는 요청. 발행한 트랜잭션이 커밋된 뒤 {@link PushSender}가 보낸다.
 * url은 알림을 눌렀을 때 열 앱 안의 경로다(예: /notices/crawled/12, /life/chats/3).
 */
public record PushRequested(Collection<Long> userIds, String title, String body, String url) {}
