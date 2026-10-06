package com.moagwa.crawler.parser;

import java.time.LocalDate;

/** One row of a board list page. {@code category} is the board's own label (e.g. "일반", "취창업"), or null. */
public record ListItem(String externalId, String title, String url, LocalDate publishedDate, boolean pinned,
                       String category) {
}
