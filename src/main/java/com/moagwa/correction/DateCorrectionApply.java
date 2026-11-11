package com.moagwa.correction;

import com.moagwa.common.exception.ApiException;
import java.time.Instant;

/** 운영자가 원문을 확인해 확정한 값. null이면 해당 날짜 없음으로 저장 */
record DateCorrectionApply(Instant deadlineAt, Instant eventStartAt, Instant eventEndAt) {

    void validateEvent() {
        if (eventEndAt != null && (eventStartAt == null || eventEndAt.isBefore(eventStartAt))) {
            throw ApiException.badRequest("종료 일시는 시작 일시와 같거나 이후로 입력해 주세요.");
        }
    }
}
