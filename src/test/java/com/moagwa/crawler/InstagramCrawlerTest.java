package com.moagwa.crawler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class InstagramCrawlerTest {
    private final InstagramAccountCatalog catalog = mock(InstagramAccountCatalog.class);
    private final InstagramLinkCollector links = mock(InstagramLinkCollector.class);
    private final InstagramPostDetailsCollector details = mock(InstagramPostDetailsCollector.class);
    private final InstagramImageStorage storage = mock(InstagramImageStorage.class);
    private final InstagramPersistence persistence = mock(InstagramPersistence.class);
    private final InstagramCrawlerProperties properties = new InstagramCrawlerProperties(true, null, null, 3, null, null, null, null);
    private final InstagramCrawler crawler = new InstagramCrawler(catalog, links, properties, details, storage, persistence);
    private final InstagramAccountCatalog.InstagramAccount first = new InstagramAccountCatalog.InstagramAccount("첫 학과", "first", "https://www.instagram.com/first/");
    private final InstagramAccountCatalog.InstagramAccount second = new InstagramAccountCatalog.InstagramAccount("다음 학과", "second", "https://www.instagram.com/second/");

    @Test void finishesSavingOneAccountBeforeStartingTheNext() {
        when(catalog.accounts()).thenReturn(List.of(first, second));
        var link = new InstagramLinkCollector.InstagramPostLink("first", "TEST", "https://www.instagram.com/p/TEST/");
        var post = new InstagramPostDetailsCollector.InstagramPost("first", "TEST", "제목", link.url(), Instant.now(), "본문", List.of("https://cdn.example/a.jpg"));
        when(links.collect(List.of(first), 3)).thenReturn(Map.of(first, List.of(link)));
        when(links.collect(List.of(second), 3)).thenReturn(Map.of(second, List.of()));
        when(persistence.newPostLinks(first, List.of(link))).thenReturn(List.of(link));
        when(persistence.newPostLinks(second, List.of())).thenReturn(List.of());
        when(details.collect(List.of(link))).thenReturn(List.of(post));
        when(storage.storeImages(List.of(post))).thenReturn(Map.of("TEST", List.of("/uploads/a.jpg")));
        crawler.crawl();
        var order = inOrder(links, persistence);
        order.verify(links).collect(List.of(first), 3);
        order.verify(persistence).newPostLinks(first, List.of(link));
        order.verify(persistence).importPosts(eq(first), argThat(posts -> posts.size() == 1 && posts.get(0).imageUrls().equals(List.of("/uploads/a.jpg"))));
        order.verify(links).collect(List.of(second), 3);
    }

    @Test void accountFailureDoesNotPreventTheNextAccountFromRunning() {
        when(catalog.accounts()).thenReturn(List.of(first, second));
        when(links.collect(List.of(first), 3)).thenThrow(new IllegalStateException("failed"));
        when(links.collect(List.of(second), 3)).thenReturn(Map.of(second, List.of()));
        when(persistence.newPostLinks(second, List.of())).thenReturn(List.of());
        crawler.crawl();
        verify(persistence).markFailure(eq(first), anyString());
        verify(persistence).importPosts(second, List.of());
        verify(persistence, never()).markFailure(eq(second), anyString());
    }

    @Test void splitsLargeAccountIntoDetailBatchesWithoutLimitingTotalPosts() {
        var allLinks = java.util.stream.IntStream.range(0, 151).mapToObj(i ->
                new InstagramLinkCollector.InstagramPostLink("first", "POST_" + i,
                        "https://www.instagram.com/p/POST_" + i + "/")).toList();
        when(catalog.accounts()).thenReturn(List.of(first));
        when(links.collect(List.of(first), 3)).thenReturn(Map.of(first, allLinks));
        when(persistence.newPostLinks(first, allLinks)).thenReturn(allLinks);
        when(details.collect(anyList())).thenAnswer(invocation -> {
            List<InstagramLinkCollector.InstagramPostLink> batch = invocation.getArgument(0);
            return batch.stream().map(link -> new InstagramPostDetailsCollector.InstagramPost(
                    "first", link.externalId(), "제목", link.url(), Instant.now(), "본문",
                    List.of("/uploads/image.jpg"))).toList();
        });
        when(storage.storeImages(anyList())).thenReturn(Map.of());
        crawler.crawl();
        verify(details).collect(allLinks.subList(0, 150));
        verify(details).collect(allLinks.subList(150, 151));
        verify(persistence).importPosts(eq(first), argThat(posts -> posts.size() == 150));
        verify(persistence).importPosts(eq(first), argThat(posts -> posts.size() == 1));
        verify(persistence, never()).markFailure(any(), anyString());
    }

    @Test void doesNotSavePostsWhoseDateCouldNotBeRead() {
        when(catalog.accounts()).thenReturn(List.of(first));
        var link = new InstagramLinkCollector.InstagramPostLink("first", "TEST", "https://www.instagram.com/p/TEST/");
        var undated = new InstagramPostDetailsCollector.InstagramPost("first", "TEST", "제목", link.url(), null, "본문",
                List.of("https://cdn.example/a.jpg"));
        when(links.collect(List.of(first), 3)).thenReturn(Map.of(first, List.of(link)));
        when(persistence.newPostLinks(first, List.of(link))).thenReturn(List.of(link));
        when(details.collect(List.of(link))).thenReturn(List.of(undated));
        crawler.crawl();
        verify(persistence, never()).importPosts(eq(first), anyList());
        verify(persistence).markFailure(eq(first), anyString());
    }

    @Test void stopsFurtherRequestsWhenInstagramRateLimitsTheRun() {
        when(catalog.accounts()).thenReturn(List.of(first, second));
        when(links.collect(List.of(first), 3)).thenThrow(new IllegalStateException("INSTAGRAM_RATE_LIMIT"));
        crawler.crawl();
        verify(persistence).markFailure(eq(first), anyString());
        verify(links, never()).collect(List.of(second), 3);
    }
}
