package com.moagwa.feed;

import java.time.Instant;

public record ScheduleEntry(String type, Instant at, String source, long noticeId, String title,
                            String organizationName, String location) {
}
