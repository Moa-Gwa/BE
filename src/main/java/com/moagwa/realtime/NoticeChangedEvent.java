package com.moagwa.realtime;

/** Something about a notice changed; clients refetch. Published after the transaction commits. */
public record NoticeChangedEvent(String type, String source, long noticeId) {

    public static final String CREATED = "created";
    public static final String UPDATED = "updated";
    public static final String CLOSED = "closed";
    public static final String DELETED = "deleted";
}
