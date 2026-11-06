package com.moagwa.market;

import java.util.List;

/** 목록 페이지 범위. 다음 페이지가 있는지 알기 위해 한 건을 더 읽는다. */
record PageWindow(int page, int size) {

    private static final int MAX_SIZE = 50;

    static PageWindow of(int page, int size) {
        return new PageWindow(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
    }

    int fetchLimit() {
        return size + 1;
    }

    int offset() {
        return page * size;
    }

    boolean hasNext(List<?> fetched) {
        return fetched.size() > size;
    }

    <T> List<T> content(List<T> fetched) {
        return hasNext(fetched) ? fetched.subList(0, size) : fetched;
    }
}
