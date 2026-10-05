package com.moagwa.feed;

import java.util.List;

public record FeedPage(List<FeedItem> items, int page, int size, boolean hasNext) {
}
