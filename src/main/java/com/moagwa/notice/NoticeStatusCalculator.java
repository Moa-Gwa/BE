package com.moagwa.notice;

import java.time.Duration;
import java.time.Instant;

/** Java mirror of the display_status CASE expression in the v_feed_items view; keep them in sync. */
public final class NoticeStatusCalculator {

    private static final Duration SOON = Duration.ofHours(24);

    private NoticeStatusCalculator() {
    }

    /** Returns null when the notice has neither event nor deadline times (no badge is shown). */
    public static DisplayStatus display(NoticeStatus status, Instant eventStartAt, Instant eventEndAt,
                                        Instant deadlineAt, Instant now) {
        if (status == NoticeStatus.CLOSED || (deadlineAt != null && deadlineAt.isBefore(now))) {
            return DisplayStatus.CLOSED;
        }
        if (eventEndAt != null && eventEndAt.isBefore(now)) {
            return DisplayStatus.ENDED;
        }
        if (deadlineAt != null && Duration.between(now, deadlineAt).compareTo(SOON) <= 0) {
            return DisplayStatus.DEADLINE_SOON;
        }
        if (eventStartAt != null) {
            return eventStartAt.isAfter(now) ? DisplayStatus.UPCOMING : DisplayStatus.ONGOING;
        }
        return null;
    }
}
