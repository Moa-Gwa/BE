package com.moagwa.crawler;

import java.util.Map;

/**
 * Maps a crawled board's own category label to one of our category codes.
 * Only labels we deliberately unify are listed; everything else stays uncategorized.
 * (학과 게시판의 "커리어" 분류는 의도적으로 묶지 않는다.)
 */
final class BoardCategoryMapper {

    private static final Map<String, String> LABEL_TO_CODE = Map.of(
            "취창업", "CAREER");

    private BoardCategoryMapper() {
    }

    static String toCode(String boardLabel) {
        return boardLabel == null ? null : LABEL_TO_CODE.get(boardLabel.replace(" ", ""));
    }
}
