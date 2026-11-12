package com.moagwa.feed;

import java.time.Instant;

/** bookmarked = 로그인한 학생이 저장한 공지 여부 */
public record ScheduleEntry(String type, Instant at, String source, long noticeId, String title,
                            String organizationName, String location, boolean bookmarked, String memo) {
    public ScheduleEntry(String type, Instant at, String source, long noticeId, String title,
                         String organizationName, String location, boolean bookmarked) {
        this(type, at, source, noticeId, title, organizationName, location, bookmarked, null);
    }
}
