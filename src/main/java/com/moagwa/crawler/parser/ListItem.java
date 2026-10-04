package com.moagwa.crawler.parser;

import java.time.LocalDate;

public record ListItem(String externalId, String title, String url, LocalDate publishedDate, boolean pinned) {
}
