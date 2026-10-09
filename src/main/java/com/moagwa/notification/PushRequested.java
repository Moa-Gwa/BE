package com.moagwa.notification;

import java.util.Collection;

/** Published once the in-app notification rows are saved; {@link PushSender} delivers it after the commit. */
record PushRequested(Collection<Long> userIds, String source, long noticeId, String title, String body) {}
