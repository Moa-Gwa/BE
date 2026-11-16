package com.moagwa.crawler;

import java.util.Map;

/** 원문 게시판의 명시적 분류만 변환한다. 제목·본문으로 추측하거나 일반 공지를 기타로 바꾸지 않는다. */
final class BoardCategoryMapper {
    private static final Map<String, String> LABEL_TO_CODE = Map.ofEntries(
            Map.entry("학사", "ACADEMIC"),
            Map.entry("장학", "SCHOLARSHIP"), Map.entry("장학금", "SCHOLARSHIP"),
            Map.entry("복지", "WELFARE"), Map.entry("행사", "EVENT"),
            Map.entry("간식", "SNACK"), Map.entry("제휴", "PARTNERSHIP"),
            Map.entry("학생회비", "STUDENT_FEE"), Map.entry("모집", "RECRUIT"),
            Map.entry("취창업", "CAREER"), Map.entry("취업", "CAREER"),
            Map.entry("창업", "CAREER"), Map.entry("취업·창업", "CAREER"),
            Map.entry("기타", "ETC"));

    private BoardCategoryMapper() {}

    static String toCode(String boardLabel) {
        // 공백과 NBSP를 정규화하되 모호한 "일반", "공지", "커리어" 등은 미분류로 유지한다.
        return boardLabel == null ? null : LABEL_TO_CODE.get(boardLabel.replaceAll("[\\s\\p{Z}]+", ""));
    }
}
