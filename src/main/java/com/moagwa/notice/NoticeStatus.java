package com.moagwa.notice;

/** Stored lifecycle state. Display states (upcoming, ongoing, ...) are computed, see {@link NoticeStatusCalculator}. */
public enum NoticeStatus {
    DRAFT,
    SCHEDULED,
    PUBLISHED,
    CLOSED
}
