package com.moagwa.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoticeExtractorTest {

    private static final List<String> CATEGORIES = List.of("ACADEMIC", "SCHOLARSHIP", "EVENT", "ETC");
    /** 2026-11-10 09:00 KST 게시 */
    private static final Instant PUBLISHED = LocalDateTime.of(2026, 11, 10, 9, 0).atZone(NoticeExtractor.KST).toInstant();
    private static final String TITLE = "2026학년도 2학기 국가장학금 2차 신청 안내";
    private static final String TEXT = """
            신청 기간: 2026. 11. 10.(화) ~ 12. 7.(월)
              17:00까지
            설명회: 11월 20일(금) 14:00, 중앙도서관
            * 장학 혜택은 졸업시까지 유지
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final NoticeExtractor extractor = new NoticeExtractor(LocalTime.of(18, 0));

    @Test
    void groundedDatesAreSavedInKoreanTime() {
        ObjectNode r = answer("HIGH");
        r.put("deadlineDate", "2026-12-07").put("deadlineTime", "17:00")
                .put("deadlineEvidence", "~ 12. 7.(월) 17:00까지"); // 줄바꿈·공백 차이는 무시
        r.put("eventStartDate", "2026-11-20").put("eventStartTime", "14:00")
                .put("eventEvidence", "설명회: 11월 20일(금) 14:00");

        NoticeExtractor.Result result = parse(r);

        assertThat(result.deadlineAt()).isEqualTo(kst(2026, 12, 7, 17, 0));
        assertThat(result.eventStartAt()).isEqualTo(kst(2026, 11, 20, 14, 0));
        assertThat(result.eventEndAt()).isEqualTo(kst(2026, 11, 20, 23, 59)); // 종료일 없으면 하루짜리
        assertThat(result.evidence()).isEqualTo("~ 12. 7.(월) 17:00까지 / 설명회: 11월 20일(금) 14:00");
        assertThat(result.category()).isEqualTo("SCHOLARSHIP");
        assertThat(result.keywords()).containsExactly("국가장학금", "2차 신청");
    }

    @Test
    void dateWithoutTimeUsesTheDefaultDeadlineTime() {
        ObjectNode r = answer("MEDIUM");
        r.put("deadlineDate", "2026-12-07").put("deadlineEvidence", "12. 7.(월)");

        assertThat(parse(r).deadlineAt()).isEqualTo(kst(2026, 12, 7, 18, 0));
    }

    @Test
    void evidenceNotInTheBodyIsRejected() {
        ObjectNode r = answer("HIGH");
        r.put("deadlineDate", "2026-12-07").put("deadlineEvidence", "12월 7일까지 제출"); // 본문에 없는 문장

        NoticeExtractor.Result result = parse(r);

        assertThat(result.deadlineAt()).isNull();
        assertThat(result.evidence()).isNull();
        assertThat(result.category()).isEqualTo("SCHOLARSHIP"); // 카테고리·키워드는 그대로 저장
    }

    @Test
    void lowConfidenceDropsDatesButKeepsCategoryAndKeywords() {
        ObjectNode r = answer("LOW");
        r.put("deadlineDate", "2026-12-07").put("deadlineEvidence", "12. 7.(월)");

        NoticeExtractor.Result result = parse(r);

        assertThat(result.deadlineAt()).isNull();
        assertThat(result.keywords()).isNotEmpty();
    }

    @Test
    void datesFarFromThePublishDateAreRejected() {
        ObjectNode r = answer("HIGH");
        r.put("deadlineDate", "2025-12-07").put("deadlineEvidence", "12. 7.(월)"); // 연도를 잘못 고른 경우
        assertThat(parse(r).deadlineAt()).isNull();

        r.put("deadlineDate", "2028-12-07"); // "졸업시까지" 같은 먼 기한
        assertThat(parse(r).deadlineAt()).isNull();

        r.put("deadlineDate", "12/7"); // 형식 오류
        assertThat(parse(r).deadlineAt()).isNull();

        r.put("deadlineDate", "2026-12-07T17:00:00"); // 시각을 붙여 답한 경우 날짜만 사용
        assertThat(parse(r).deadlineAt()).isEqualTo(kst(2026, 12, 7, 18, 0));
    }

    @Test
    void eventEndingBeforeItStartsIsRejected() {
        ObjectNode r = answer("HIGH");
        r.put("eventStartDate", "2026-11-20").put("eventEndDate", "2026-11-19")
                .put("eventEvidence", "설명회: 11월 20일(금) 14:00");

        NoticeExtractor.Result result = parse(r);

        assertThat(result.eventStartAt()).isNull();
        assertThat(result.eventEndAt()).isNull();
    }

    @Test
    void unknownCategoryAndExtraKeywordsAreDropped() {
        ObjectNode r = answer("HIGH");
        r.put("category", "WEAPON");
        r.putArray("keywords").add("a").add("b").add("c").add("a").add("d").add("e").add("f")
                .add("스물한글자가넘는아주아주아주긴키워드입니다요");

        NoticeExtractor.Result result = parse(r);

        assertThat(result.category()).isNull();
        assertThat(result.keywords()).containsExactly("a", "b", "c", "d", "e");
    }

    @Test
    void promptCarriesThePublishDateAndCategories() {
        String prompt = extractor.prompt(TITLE, TEXT, PUBLISHED, CATEGORIES);

        assertThat(prompt).contains("게시일은 2026-11-10").contains("ACADEMIC, SCHOLARSHIP, EVENT, ETC")
                .contains(TITLE).contains("17:00까지");
    }

    private NoticeExtractor.Result parse(JsonNode r) {
        return extractor.parse(r, TITLE, TEXT, PUBLISHED, CATEGORIES);
    }

    private ObjectNode answer(String confidence) {
        ObjectNode r = json.createObjectNode();
        r.put("category", "SCHOLARSHIP").put("confidence", confidence);
        r.putArray("keywords").add("국가장학금").add("2차 신청");
        return r;
    }

    private static Instant kst(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(NoticeExtractor.KST).toInstant();
    }
}
