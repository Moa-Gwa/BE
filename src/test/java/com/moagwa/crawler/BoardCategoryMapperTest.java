package com.moagwa.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardCategoryMapperTest {
    @ParameterizedTest
    @CsvSource({"학사,ACADEMIC", "장학,SCHOLARSHIP", "장학금,SCHOLARSHIP", "복지,WELFARE", "행사,EVENT",
        "간식,SNACK", "제휴,PARTNERSHIP", "학생회비,STUDENT_FEE", "모집,RECRUIT", "취창업,CAREER",
        "취업,CAREER", "창업,CAREER", "취업·창업,CAREER", "기타,ETC"})
    void mapsOnlyExplicitBoardLabels(String label, String code) {
        assertThat(BoardCategoryMapper.toCode(label)).isEqualTo(code);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "일반", "공지", "전체", "학과공지", "커리어", "장학금 신청 안내", "새로운분류"})
    void leavesUnknownOrMissingLabelsUncategorized(String label) {
        assertThat(BoardCategoryMapper.toCode(label)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {" 장학 ", "장 학", "\t장학\n", "\u00a0장학\u00a0", "\u3000장학\u3000"})
    void normalizesWhitespace(String label) {
        assertThat(BoardCategoryMapper.toCode(label)).isEqualTo("SCHOLARSHIP");
    }
}
