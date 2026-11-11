package com.moagwa.correction;

import java.time.Instant;

/** 운영자 검토 화면용. 원문(contentText)과 현재 저장된 날짜·근거 문장을 함께 내려줌 */
record DateCorrectionView(long id, String field, String suggestedStart, String suggestedEnd, String memo,
                          String status, Instant createdAt, Notice notice) {

    record Notice(long id, String title, String url, Instant publishedAt, Instant deadlineAt, Instant eventStartAt,
                  Instant eventEndAt, String dateEvidence, String contentText) {
    }

    record Created(long id, String status) {
    }
}
