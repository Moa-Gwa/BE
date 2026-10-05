package com.moagwa.notice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NoticeStatusCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");

    private static DisplayStatus status(NoticeStatus s, Instant start, Instant end, Instant deadline) {
        return NoticeStatusCalculator.display(s, start, end, deadline, NOW);
    }

    @Test
    void noTimesMeansNoBadge() {
        assertThat(status(NoticeStatus.PUBLISHED, null, null, null)).isNull();
    }

    @Test
    void upcomingBeforeEventStart() {
        assertThat(status(NoticeStatus.PUBLISHED, NOW.plus(Duration.ofDays(3)), null, null))
                .isEqualTo(DisplayStatus.UPCOMING);
    }

    @Test
    void ongoingBetweenStartAndEnd() {
        assertThat(status(NoticeStatus.PUBLISHED, NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), null))
                .isEqualTo(DisplayStatus.ONGOING);
    }

    @Test
    void endedAfterEventEnd() {
        assertThat(status(NoticeStatus.PUBLISHED, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)), null))
                .isEqualTo(DisplayStatus.ENDED);
    }

    @Test
    void deadlineSoonWithin24HoursButNotAfter() {
        assertThat(status(NoticeStatus.PUBLISHED, null, null, NOW.plus(Duration.ofHours(24))))
                .isEqualTo(DisplayStatus.DEADLINE_SOON);
        assertThat(status(NoticeStatus.PUBLISHED, null, null, NOW.plus(Duration.ofHours(25)))).isNull();
    }

    @Test
    void closedWhenManuallyClosedOrDeadlinePassed() {
        assertThat(status(NoticeStatus.CLOSED, null, null, null)).isEqualTo(DisplayStatus.CLOSED);
        assertThat(status(NoticeStatus.PUBLISHED, null, null, NOW.minusSeconds(1))).isEqualTo(DisplayStatus.CLOSED);
    }

    @Test
    void deadlineSoonWinsOverOngoing() {
        assertThat(status(NoticeStatus.PUBLISHED, NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(2)),
                NOW.plus(Duration.ofHours(3)))).isEqualTo(DisplayStatus.DEADLINE_SOON);
    }
}
