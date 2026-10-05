package com.moagwa.notice;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One block of a notice body. TEXT blocks carry {@code text}; IMAGES blocks carry {@code images} shown
 * either as a swipeable CAROUSEL or a vertical STACK.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NoticeBlock(String type, String text, List<String> images, String layout) {

    public static final String TEXT = "TEXT";
    public static final String IMAGES = "IMAGES";
    public static final String CAROUSEL = "CAROUSEL";
    public static final String STACK = "STACK";
}
