package com.moagwa.crawler;

import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawlStatus;
import com.moagwa.crawler.domain.CrawledNotice;
import com.moagwa.crawler.fetch.PageFetcher;
import com.moagwa.crawler.parser.BoardParser;
import com.moagwa.crawler.parser.DetailContent;
import com.moagwa.crawler.parser.ListItem;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlerService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CrawlSourceRepository sources;
    private final CrawlPersistence persistence;
    private final PageFetcher fetcher;
    private final BoardParser parser;
    private final ApplicationEventPublisher events;
    private final CrawlerProperties properties;
    private final Clock clock;

    /** Crawls every enabled source, at most {@code concurrentLimit} at a time. Failures are isolated per source. */
    public List<CrawlResult> crawlAll() {
        List<CrawlSource> enabled = sources.findAllEnabled();
        if (enabled.isEmpty()) {
            log.warn("no enabled crawl sources");
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(properties.concurrentLimit());
        try {
            List<CompletableFuture<CrawlResult>> futures = enabled.stream()
                    .map(s -> CompletableFuture.supplyAsync(() -> crawlSource(s), pool))
                    .toList();
            List<CrawlResult> results = futures.stream().map(CompletableFuture::join).toList();
            long failed = results.stream().filter(CrawlResult::isFailed).count();
            int created = results.stream().mapToInt(CrawlResult::newNotices).sum();
            log.info("crawl finished: {} sources, {} failed, {} new notices", results.size(), failed, created);
            return results;
        } finally {
            pool.shutdown();
        }
    }

    /** Crawls one source. Never throws; the outcome is returned and recorded on the source. */
    public CrawlResult crawlSource(CrawlSource source) {
        try {
            boolean firstCrawl = source.isFirstCrawl();
            Document listPage = fetchWithRetry(source.getListUrl());
            List<ListItem> items = parser.parseList(listPage, source);
            if (items.isEmpty()) {
                log.warn("[{}] no rows parsed; the site layout may have changed", source.getCode());
                persistence.recordSuccess(source.getId(), clock.instant(), true);
                return new CrawlResult(source.getCode(), CrawlStatus.EMPTY, 0, null);
            }

            retryMissingContent(source);
            int created = saveNewItems(source, items, firstCrawl);

            persistence.recordSuccess(source.getId(), clock.instant(), false);
            return new CrawlResult(source.getCode(), CrawlStatus.SUCCESS, created, null);
        } catch (Exception e) {
            log.error("[{}] crawl failed: {}", source.getCode(), e.getMessage());
            persistence.recordFailure(source.getId(), e.getMessage());
            return new CrawlResult(source.getCode(), CrawlStatus.FAILED, 0, e.getMessage());
        }
    }

    private int saveNewItems(CrawlSource source, List<ListItem> items, boolean firstCrawl) {
        Set<String> existing = persistence.findExistingExternalIds(source.getId(),
                items.stream().map(ListItem::externalId).toList());
        List<ListItem> fresh = new ArrayList<>(items.stream().filter(i -> !existing.contains(i.externalId())).toList());
        java.util.Collections.reverse(fresh); // oldest first so events arrive in publish order

        int created = 0;
        for (ListItem item : fresh) {
            Instant now = clock.instant();
            Instant publishedAt = item.publishedDate() != null
                    ? item.publishedDate().atStartOfDay(KST).toInstant()
                    : now;
            Optional<DetailContent> content = fetchDetail(source, item.url());
            CrawledNotice notice = new CrawledNotice(source, item.externalId(), item.title(), item.url(),
                    item.pinned(), publishedAt, now);
            try {
                CrawledNotice saved = persistence.saveNew(notice, content.orElse(null));
                created++;
                if (!firstCrawl) {
                    events.publishEvent(new CrawledNoticeCreatedEvent(saved.getId(), source.getId(),
                            source.getOrganization().getId(), saved.getTitle()));
                }
            } catch (DataIntegrityViolationException duplicate) {
                log.debug("[{}] {} already stored by a concurrent run", source.getCode(), item.externalId());
            }
        }
        return created;
    }

    /** Notices saved earlier without a body (detail fetch failed) get another chance on every run. */
    private void retryMissingContent(CrawlSource source) {
        for (CrawledNotice notice : persistence.findUnfetched(source.getId(), properties.detailRetryBatch())) {
            fetchDetail(source, notice.getUrl())
                    .ifPresent(content -> persistence.applyContent(notice.getId(), content));
        }
    }

    private Optional<DetailContent> fetchDetail(CrawlSource source, String url) {
        try {
            return parser.parseDetail(fetchWithRetry(url), source);
        } catch (Exception e) {
            log.warn("[{}] detail fetch failed for {}: {}", source.getCode(), url, e.getMessage());
            return Optional.empty();
        }
    }

    private Document fetchWithRetry(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= properties.maxRetries(); attempt++) {
            try {
                return fetcher.fetch(url);
            } catch (IOException e) {
                last = e;
                log.warn("fetch {} failed ({}/{}): {}", url, attempt, properties.maxRetries(), e.getMessage());
                if (attempt < properties.maxRetries()) {
                    Thread.sleep(properties.retryDelay().toMillis());
                }
            }
        }
        throw last;
    }
}
