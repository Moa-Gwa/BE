package com.moagwa.feed;

import java.time.Instant;

/** bookmarked = 로그인한 학생이 저장한 공지 여부 */
public record ScheduleEntry(String type, Instant at, String source, long noticeId, String title,
                            String organizationName, String location, boolean bookmarked, String memo, String sourceUrl, Instant endAt, String color) {
    public ScheduleEntry withColor(String color) {
        return new ScheduleEntry(type, at, source, noticeId, title, organizationName, location, bookmarked, memo, sourceUrl, endAt, color);
    }
    public ScheduleEntry(String type, Instant at, String source, long noticeId, String title,
                         String organizationName, String location, boolean bookmarked, String memo, String sourceUrl, Instant endAt) {
        this(type, at, source, noticeId, title, organizationName, location, bookmarked, memo, sourceUrl, endAt, null);
    }
    public ScheduleEntry(String type, Instant at, String source, long noticeId, String title,
                         String organizationName, String location, boolean bookmarked, String memo, String sourceUrl) {
        this(type, at, source, noticeId, title, organizationName, location, bookmarked, memo, sourceUrl, null);
    }
    public ScheduleEntry(String type, Instant at, String source, long noticeId, String title,
                         String organizationName, String location, boolean bookmarked, String memo) {
        this(type, at, source, noticeId, title, organizationName, location, bookmarked, memo, null, null);
    }
    public ScheduleEntry(String type, Instant at, String source, long noticeId, String title,
                         String organizationName, String location, boolean bookmarked) {
        this(type, at, source, noticeId, title, organizationName, location, bookmarked, null, null, null);
    }
}
