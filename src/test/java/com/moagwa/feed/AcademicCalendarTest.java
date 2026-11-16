package com.moagwa.feed;

import com.moagwa.crawler.fetch.PageFetcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicCalendarTest {
    String html = """
        <div class="b-cal-list-box">
        <div><p>6월</p><div>
          <div><p>23 (화) ~ 07.13(월)</p><ul><li>계절학기 수업<div class="b-cal-list-cate"><span class="b-campus01">성심</span></div></li></ul></div>
          <div><p>25 (목)</p><ul><li>성의 전용<div class="b-cal-list-cate"><span class="b-campus02">성의</span></div></li></ul></div>
          <div><p>30 (화)</p><ul><li>공통 행사<div class="b-cal-list-cate"><span class="b-campus01">성심</span><span class="b-campus03">성신</span></div></li></ul></div>
        </div></div>
        <div><p>1월</p><div><div><p>4 (월) ~ 8 (금)</p><ul><li>복학원 제출기간<div class="b-cal-list-cate"><span class="b-campus01">성심</span></div></li></ul></div></div></div>
        </div>
        """;

    @Test void parsesOnlySeongsimAndCommonEventsWithCrossMonthAndNextYearDates() {
        var events = AcademicCalendar.parse(Jsoup.parse(html), 2026);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).from()).isEqualTo(LocalDate.of(2026, 6, 23));
        assertThat(events.get(0).to()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(events.get(1).title()).isEqualTo("공통 행사");
        assertThat(events.get(2).from()).isEqualTo(LocalDate.of(2027, 1, 4));
    }

    @Test void returnsOneFullPeriodForAnOverlappingQueryAndCachesAnnualRequests() throws Exception {
        PageFetcher fetcher = mock(PageFetcher.class);
        when(fetcher.fetch(AcademicCalendar.url(2026))).thenReturn(Jsoup.parse(html));
        var calendar = new AcademicCalendar(fetcher);
        var entries = calendar.entries(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3));
        assertThat(entries).hasSize(1).allSatisfy(e -> {
            assertThat(e.source()).isEqualTo("ACADEMIC");
            assertThat(e.bookmarked()).isFalse();
            assertThat(e.sourceUrl()).isEqualTo(AcademicCalendar.url(2026));
        });
        assertThat(entries.get(0).at()).isEqualTo(Instant.parse("2026-06-22T15:00:00Z"));
        assertThat(entries.get(0).endAt()).isEqualTo(Instant.parse("2026-07-13T15:00:00Z"));
        calendar.entries(LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 9));
        verify(fetcher, times(1)).fetch(AcademicCalendar.url(2026));
    }

    @Test void configuredFetchUrlIsUsedWhileEntriesKeepTheOfficialLink() throws Exception {
        PageFetcher fetcher = mock(PageFetcher.class);
        when(fetcher.fetch("http://localhost:3101/academic-2026.html")).thenReturn(Jsoup.parse(html));
        var entries = new AcademicCalendar(fetcher, "http://localhost:3101/academic-{year}.html")
                .entries(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3));
        assertThat(entries).hasSize(1).allSatisfy(e -> assertThat(e.sourceUrl()).isEqualTo(AcademicCalendar.url(2026)));
        verify(fetcher, never()).fetch(AcademicCalendar.url(2026));
    }

    @Test void realOfficialFixtureHasSeongsimFinalsAndNoOtherCampusOnlyEvents() throws Exception {
        try (var in = getClass().getResourceAsStream("/academic/seongsim-2026.html")) {
            var events = AcademicCalendar.parse(Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)), 2026);
            assertThat(events.size()).isGreaterThan(40);
            assertThat(events).anySatisfy(e -> {
                assertThat(e.title()).isEqualTo("기말고사");
                assertThat(e.from()).isEqualTo(LocalDate.of(2026, 6, 16));
                assertThat(e.to()).isEqualTo(LocalDate.of(2026, 6, 22));
            });
            assertThat(events).noneMatch(e -> e.title().contains("White Coat") || e.title().contains("신학과 성주간"));
        }
    }

    @Test void refreshFailureRetainsPreviousDatesAndBacksOff() throws Exception {
        PageFetcher fetcher = mock(PageFetcher.class);
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(fetcher.fetch(anyString())).thenReturn(Jsoup.parse(html));
        var calendar = new AcademicCalendar(fetcher, clock);
        var from = LocalDate.of(2026, 7, 1);
        var before = calendar.entries(from, from.plusDays(1));
        when(clock.instant()).thenReturn(now.plus(Duration.ofHours(7)));
        when(fetcher.fetch(anyString())).thenThrow(new IOException("offline"));
        assertThat(calendar.entries(from, from.plusDays(1))).isEqualTo(before);
        calendar.entries(from, from.plusDays(1));
        verify(fetcher, times(2)).fetch(anyString());
    }

    @Test void malformedOrEmptyPagesAreNotAcceptedAsSuccessfulRefreshes() {
        assertThatThrownBy(() -> AcademicCalendar.parse(Jsoup.parse("<h1>점검 중</h1>"), 2026)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AcademicCalendar.parse(Jsoup.parse("<div class=b-cal-list-box></div>"), 2026)).isInstanceOf(IllegalArgumentException.class);
    }
}
