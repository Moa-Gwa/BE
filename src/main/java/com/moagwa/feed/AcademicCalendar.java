package com.moagwa.feed;

import com.moagwa.crawler.fetch.PageFetcher;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Official March–February academic years. One cached list request per year every six hours. */
@Service
public class AcademicCalendar {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Pattern DATE = Pattern.compile("(?:(\\d{1,2})\\s*\\.\\s*)?(\\d{1,2})\\s*\\([월화수목금토일]\\)");
    private final PageFetcher fetcher;
    private final Clock clock;
    // 비어 있으면 학교 사이트에서 수집. E2E 등에서 고정된 테스트 페이지를 쓸 때만 지정({year} 치환)
    private final String fetchUrl;
    private final Map<Integer, Cached> cache = new LinkedHashMap<>();
    private record Cached(List<Event> events, Instant refreshAt) {}
    record Event(String title, LocalDate from, LocalDate to, int year) {}

    @Autowired
    public AcademicCalendar(PageFetcher fetcher, @Value("${moagwa.academic-calendar.fetch-url:}") String fetchUrl) {
        this.fetcher = fetcher; this.clock = Clock.systemUTC(); this.fetchUrl = fetchUrl;
    }
    public AcademicCalendar(PageFetcher fetcher) { this(fetcher, ""); }
    AcademicCalendar(PageFetcher fetcher, Clock clock) { this.fetcher = fetcher; this.clock = clock; this.fetchUrl = ""; }

    public static String url(int year) {
        return "https://www.catholic.ac.kr/ko/support/calendar2024_list.do?mode=list&selectedYear=" + year + "&siteId=ko";
    }

    public List<ScheduleEntry> entries(LocalDate from, LocalDate toExclusive) {
        List<ScheduleEntry> result = new ArrayList<>();
        int first = academicYear(from), last = academicYear(toExclusive.minusDays(1));
        for (int year = first; year <= last; year++) {
            for (Event e : events(year)) {
                if (!e.from().isBefore(toExclusive) || e.to().isBefore(from)) continue;
                result.add(new ScheduleEntry("ACADEMIC", e.from().atStartOfDay(KST).toInstant(), "ACADEMIC",
                        Integer.toUnsignedLong(Objects.hash(e.title(), e.from(), e.to())), e.title(),
                        "성심교정 학사일정", null, false, null, url(e.year()),
                        e.to().plusDays(1).atStartOfDay(KST).toInstant()));
            }
        }
        return result;
    }

    private static int academicYear(LocalDate day) { return day.getYear() - (day.getMonthValue() < 3 ? 1 : 0); }

    private synchronized List<Event> events(int year) {
        Cached previous = cache.get(year);
        Instant now = clock.instant();
        if (previous != null && now.isBefore(previous.refreshAt())) return previous.events();
        List<Event> events = previous == null ? List.of() : previous.events();
        Duration ttl = Duration.ofHours(6);
        try {
            events = parse(fetcher.fetch(fetchUrl.isBlank() ? url(year) : fetchUrl.replace("{year}", String.valueOf(year))), year);
        } catch (IOException | IllegalArgumentException | DateTimeException ex) {
            LoggerFactory.getLogger(AcademicCalendar.class).warn("학사일정 {} 수집 실패: {}", year, ex.toString());
            ttl = Duration.ofMinutes(5); // Keep the last successful result; avoid repeated failing requests.
        }
        cache.put(year, new Cached(events, now.plus(ttl)));
        if (cache.size() > 4) cache.remove(cache.keySet().iterator().next());
        return events;
    }

    static List<Event> parse(Document document, int year) {
        Element list = document.selectFirst(".b-cal-list-box");
        if (list == null) throw new IllegalArgumentException("학사일정 목록을 찾을 수 없습니다.");
        List<Event> events = new ArrayList<>();
        for (Element month : list.select(":root > div")) {
            Element heading = month.selectFirst(":root > p");
            if (heading == null || !heading.text().matches("\\d{1,2}월")) continue;
            int m = Integer.parseInt(heading.text().replace("월", ""));
            int actualYear = year + (m < 3 ? 1 : 0);
            for (Element row : month.select(":root > div > div")) {
                Element dates = row.selectFirst(":root > p");
                if (dates == null) continue;
                var matcher = DATE.matcher(dates.text());
                List<LocalDate> days = new ArrayList<>();
                while (matcher.find()) {
                    int dateMonth = matcher.group(1) == null ? m : Integer.parseInt(matcher.group(1));
                    int dateYear = actualYear + (dateMonth < m ? 1 : 0);
                    days.add(LocalDate.of(dateYear, dateMonth, Integer.parseInt(matcher.group(2))));
                }
                if (days.isEmpty() || days.size() > 2) throw new IllegalArgumentException("학사일정 날짜 형식 변경: " + dates.text());
                LocalDate start = days.get(0), end = days.get(days.size() - 1);
                if (end.isBefore(start) || end.isAfter(start.plusYears(1))) throw new IllegalArgumentException("잘못된 학사일정 기간");
                for (Element item : row.select(":root > ul > li")) {
                    if (item.selectFirst(".b-campus01") == null) continue;
                    Element title = item.clone();
                    title.select(".b-cal-list-cate").remove();
                    if (!title.text().isBlank()) events.add(new Event(title.text(), start, end, year));
                }
            }
        }
        if (events.isEmpty()) throw new IllegalArgumentException("성심교정 학사일정이 비어 있습니다.");
        return events.stream().distinct().toList();
    }
}
