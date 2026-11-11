package com.moagwa.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공지 본문 → 마감일·행사일·카테고리·키워드 추출의 프롬프트와 응답 검증.
 * 모델이 지어낸 날짜를 막기 위해 근거 문장이 본문에 실제로 있는지, 날짜가 게시일 기준 범위 안인지 확인하고
 * 통과한 값만 돌려준다. 네트워크 호출은 하지 않는다.
 */
final class NoticeExtractor {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_TEXT = 8000;
    private static final int MAX_KEYWORDS = 5;
    private static final int MAX_KEYWORD_LENGTH = 20;
    /** 게시일보다 이만큼 이전 날짜는 오추출로 봄(지난 일정 언급 등) */
    private static final int MIN_DAYS_BEFORE_PUBLISH = 30;
    private static final int MAX_DAYS_AFTER_PUBLISH = 400;
    private static final Pattern NUMBER = Pattern.compile("\\d{1,4}");

    record Result(Instant deadlineAt, Instant eventStartAt, Instant eventEndAt, String evidence, String category,
                  List<String> keywords) {
    }

    private final LocalTime defaultDeadlineTime;

    NoticeExtractor(LocalTime defaultDeadlineTime) {
        this.defaultDeadlineTime = defaultDeadlineTime;
    }

    String prompt(String title, String text, Instant publishedAt, List<String> categories) {
        String body = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        return """
                대학 공지에서 신청·접수 마감일, 행사 일시, 카테고리, 검색 키워드를 추출해.

                규칙
                - 게시일은 %s(한국 시간)이다. 연도가 없는 날짜는 게시일 이후 가장 가까운 날짜로 연도를 정해.
                - 날짜는 YYYY-MM-DD, 시각은 24시간제 HH:mm. 시각이 없으면 time은 null.
                - deadline은 학생이 신청·접수·제출해야 하는 마지막 날. 제목에 마감일이 있으면 그 날짜를 우선해.
                - 마감이 여러 개면 대상 학생 모두에게 해당하는 주 신청 마감 하나를 골라. 편입생·외국인 등 일부 대상만의 추가 서류 마감은 제외해.
                - event는 행사·설명회·시험·교육 등이 실제로 열리는 일시. 하루짜리면 eventEnd는 null.
                - 신청·모집·설문 응답 기간은 event가 아니다.
                - "졸업시까지", "2028학년도까지 유지"처럼 날짜가 아닌 기한은 무시해.
                - evidence에는 날짜를 판단한 본문 문장을 고치지 말고 그대로 옮겨.
                - 확실하지 않으면 해당 날짜를 null로 두고 confidence를 LOW로 해.
                - category는 다음 중 하나: %s
                - keywords는 학생이 검색할 만한 핵심 단어 3~5개(학과·단체명 제외).

                제목: %s
                본문:
                %s
                """.formatted(LocalDate.ofInstant(publishedAt, KST), String.join(", ", categories), title, body);
    }

    static Map<String, Object> schema(List<String> categories) {
        Map<String, Object> date = Map.of("type", "STRING", "nullable", true, "description", "YYYY-MM-DD");
        Map<String, Object> time = Map.of("type", "STRING", "nullable", true, "description", "HH:mm (24시간제)");
        Map<String, Object> evidence = Map.of("type", "STRING", "nullable", true, "description", "본문 문장 그대로");
        return Map.ofEntries(
                Map.entry("type", "OBJECT"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("deadlineDate", date),
                        Map.entry("deadlineTime", time),
                        Map.entry("deadlineEvidence", evidence),
                        Map.entry("eventStartDate", date),
                        Map.entry("eventStartTime", time),
                        Map.entry("eventEndDate", date),
                        Map.entry("eventEndTime", time),
                        Map.entry("eventEvidence", evidence),
                        Map.entry("category", Map.of("type", "STRING", "enum", categories)),
                        Map.entry("keywords", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                        Map.entry("confidence", Map.of("type", "STRING", "enum", List.of("HIGH", "MEDIUM", "LOW"))))),
                Map.entry("required", List.of("category", "keywords", "confidence")));
    }

    Result parse(JsonNode r, String title, String text, Instant publishedAt, List<String> categories) {
        String haystack = squash(title + "\n" + text);
        LocalDate published = LocalDate.ofInstant(publishedAt, KST);
        boolean trustDates = !"LOW".equals(r.path("confidence").asText());

        Instant deadline = null;
        String deadlineEvidence = str(r, "deadlineEvidence");
        if (trustDates && grounded(deadlineEvidence, haystack)) {
            LocalDate date = date(str(r, "deadlineDate"), published);
            if (date != null && mentions(deadlineEvidence, date)) {
                deadline = date.atTime(timeOr(str(r, "deadlineTime"), defaultDeadlineTime)).atZone(KST).toInstant();
            }
        }

        Instant eventStart = null;
        Instant eventEnd = null;
        String eventEvidence = str(r, "eventEvidence");
        if (trustDates && grounded(eventEvidence, haystack)) {
            LocalDate startDate = date(str(r, "eventStartDate"), published);
            if (startDate != null && mentions(eventEvidence, startDate)) {
                eventStart = startDate.atTime(timeOr(str(r, "eventStartTime"), LocalTime.MIN)).atZone(KST).toInstant();
                // 종료일이 없으면 하루짜리 행사. 종료 시각(18:00~20:00 등)만 있으면 그날 종료 시각 사용
                LocalDate endDate = startDate;
                LocalTime endTime = timeOr(str(r, "eventEndTime"), LocalTime.of(23, 59));
                LocalDate answeredEnd = date(str(r, "eventEndDate"), published);
                if (answeredEnd != null && !answeredEnd.isBefore(startDate) && endMentioned(eventEvidence, startDate, answeredEnd)) {
                    endDate = answeredEnd;
                } else if (str(r, "eventEndDate") != null) {
                    // 근거 문장에서 확인되지 않은 종료일은 버리고 시작일 하루짜리로 표시
                    endTime = LocalTime.of(23, 59);
                    if (answeredEnd != null && answeredEnd.isBefore(startDate)) {
                        endDate = null;
                    }
                }
                eventEnd = endDate == null ? null : endDate.atTime(endTime).atZone(KST).toInstant();
                if (eventEnd == null || eventEnd.isBefore(eventStart)) {
                    eventStart = null;
                    eventEnd = null;
                }
            }
        }

        List<String> evidences = new ArrayList<>();
        if (deadline != null) {
            evidences.add(deadlineEvidence.trim());
        }
        if (eventStart != null && !evidences.contains(eventEvidence.trim())) {
            evidences.add(eventEvidence.trim());
        }
        String category = r.path("category").asText(null);
        return new Result(deadline, eventStart, eventEnd, evidences.isEmpty() ? null : String.join(" / ", evidences),
                categories.contains(category) ? category : null, keywords(r.path("keywords")));
    }

    /** 근거 문장이 본문에 실제로 있는지. 공백·줄바꿈 차이는 무시 */
    private static boolean grounded(String evidence, String haystack) {
        String e = squash(evidence == null ? "" : evidence);
        return e.length() >= 4 && haystack.contains(e);
    }

    /**
     * 근거 문장에 해당 날짜의 월·일이 연달아 적혀 있는지. "12. 7.", "26.10.07", "11월 20일", "10/2" 형식 모두 숫자 순서로 확인.
     * 근거 문장은 맞게 옮기고 날짜만 다르게 답한 경우를 걸러냄
     */
    private static boolean mentions(String evidence, LocalDate d) {
        List<Integer> n = numbers(evidence);
        for (int i = 0; i + 1 < n.size(); i++) {
            if (n.get(i) == d.getMonthValue() && n.get(i + 1) == d.getDayOfMonth()) {
                return true;
            }
        }
        return false;
    }

    /** 종료일은 "11월 20일 ~ 22일"처럼 같은 달이면 일만 적는 경우도 허용 */
    private static boolean endMentioned(String evidence, LocalDate start, LocalDate end) {
        return mentions(evidence, end)
                || start.getMonth() == end.getMonth() && numbers(evidence).contains(end.getDayOfMonth());
    }

    private static List<Integer> numbers(String s) {
        List<Integer> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(s);
        while (m.find()) {
            out.add(Integer.parseInt(m.group()));
        }
        return out;
    }

    /** 게시일 기준 허용 범위 밖이거나 형식이 틀리면 null */
    private static LocalDate date(String value, LocalDate published) {
        if (value == null) {
            return null;
        }
        try {
            // 2026-12-07T17:00:00처럼 시각을 붙여 답해도 날짜 부분만 사용
            LocalDate d = LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
            boolean inRange = !d.isBefore(published.minusDays(MIN_DAYS_BEFORE_PUBLISH))
                    && !d.isAfter(published.plusDays(MAX_DAYS_AFTER_PUBLISH));
            return inRange ? d : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalTime timeOr(String value, LocalTime fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return LocalTime.parse(value.length() == 4 ? "0" + value : value); // 9:00 → 09:00
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static List<String> keywords(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode k : node) {
            String word = k.asText("").trim();
            if (!word.isEmpty() && word.length() <= MAX_KEYWORD_LENGTH && out.size() < MAX_KEYWORDS) {
                out.add(word);
            }
        }
        return List.copyOf(out);
    }

    private static String str(JsonNode r, String field) {
        JsonNode v = r.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }
}
