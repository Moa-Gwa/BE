package com.moagwa.market;

import com.moagwa.common.exception.ApiException;
import java.util.List;

/** 중고거래와 같이 사기가 함께 쓰는 입력 처리. */
final class MarketInput {

    private MarketInput() {
    }

    /** 업로드한 이미지 주소만 허용한다. 외부 주소나 경로 이동은 받지 않는다. */
    static void requireUploadedImages(List<String> urls) {
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            if (url == null || !url.matches("^/uploads/[A-Za-z0-9._-]+$") || url.contains("..")) {
                throw ApiException.badRequest("업로드한 이미지만 첨부할 수 있습니다.");
            }
        }
    }

    static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 검색어의 %, _, \를 글자 그대로 찾도록 ILIKE 패턴으로 바꾼다. */
    static String likePattern(String query) {
        return "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
