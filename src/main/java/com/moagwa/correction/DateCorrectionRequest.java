package com.moagwa.correction;

import com.moagwa.common.exception.ApiException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 학생의 날짜 교정 요청. 날짜를 모두 비우면 "이 공지에는 해당 날짜가 없음"이라는 뜻.
 * DEADLINE은 suggestedStart만 사용, EVENT는 시작일과 종료일(하루짜리면 생략)
 */
record DateCorrectionRequest(
        @NotNull(message = "항목을 골라 주세요.")
        @Pattern(regexp = "DEADLINE|EVENT", message = "항목을 골라 주세요.") String field,
        LocalDate suggestedStart,
        LocalDate suggestedEnd,
        @Size(max = 300, message = "메모는 300자 이하로 입력해 주세요.") String memo) {

    void validate() {
        if ("DEADLINE".equals(field) && suggestedEnd != null) {
            throw ApiException.badRequest("마감일은 하루만 입력해 주세요.");
        }
        if (suggestedEnd != null && (suggestedStart == null || suggestedEnd.isBefore(suggestedStart))) {
            throw ApiException.badRequest("종료일은 시작일과 같거나 이후로 입력해 주세요.");
        }
        if (suggestedStart == null && memoOrNull() == null) {
            throw ApiException.badRequest("올바른 날짜를 입력하거나, 날짜가 없다면 메모를 남겨 주세요.");
        }
    }

    String memoOrNull() {
        return memo == null || memo.isBlank() ? null : memo.trim();
    }
}
