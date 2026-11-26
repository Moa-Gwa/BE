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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
    /** 기존 공지 미디어 보완 수집: 소스당 한 회차에 처리할 공지 수와 실패 후 재시도 간격 */
    private static final int MEDIA_BACKFILL_BATCH = 5;
    private static final Duration MEDIA_RETRY_AFTER = Duration.ofDays(1);

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
            retryMissingMedia(source);
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
        backfillCategories(source, items, existing);

        int created = 0;
        for (ListItem item : fresh) {
            Instant now = clock.instant();
            // 목록 날짜가 미래로 잘못 표시되면 피드 맨 위에 계속 고정되므로 수집 시각으로 대체
            Instant publishedAt = item.publishedDate() != null
                    && !item.publishedDate().isAfter(LocalDate.ofInstant(now, KST))
                    ? item.publishedDate().atStartOfDay(KST).toInstant()
                    : now;
            Optional<DetailContent> content = fetchDetail(source, item.url());
            CrawledNotice notice = new CrawledNotice(source, item.externalId(), item.title(), item.url(),
                    item.pinned(), publishedAt, now);
            notice.assignCategory(BoardCategoryMapper.toCode(item.category()));
            try {
                persistence.saveNew(notice, content.orElse(null), !firstCrawl);
                created++;
            } catch (DataIntegrityViolationException duplicate) {
                log.debug("[{}] {} already stored by a concurrent run", source.getCode(), item.externalId());
            }
        }
        return created;
    }

    /** Rows that were stored before category parsing existed get their category on the next run. */
    private void backfillCategories(CrawlSource source, List<ListItem> items, Set<String> existing) {
        java.util.Map<String, List<String>> byCode = new java.util.HashMap<>();
        for (ListItem item : items) {
            String code = BoardCategoryMapper.toCode(item.category());
            if (code != null && existing.contains(item.externalId())) {
                byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(item.externalId());
            }
        }
        byCode.forEach((code, ids) -> persistence.fillMissingCategory(source.getId(), ids, code));
    }

    /** Notices saved earlier without a body (detail fetch failed) get another chance on every run. */
    private void retryMissingContent(CrawlSource source) {
        for (CrawledNotice notice : persistence.findUnfetched(source.getId(), properties.detailRetryBatch())) {
            fetchDetail(source, notice.getUrl())
                    .ifPresent(content -> persistence.applyContent(notice.getId(), content));
        }
    }

    /**
     * 썸네일·첨부파일을 아직 확인하지 않은 기존 공지를 조금씩 보완 수집함.
     * 학교 서버에 요청이 몰리지 않도록 회차당 적은 수만 처리하고, 실패한 공지는 하루 뒤에 다시 시도함
     */
    private void retryMissingMedia(CrawlSource source) {
        Instant now = clock.instant();
        List<CrawledNotice> pending = persistence.findMissingMedia(
                source.getId(), now.minus(MEDIA_RETRY_AFTER), MEDIA_BACKFILL_BATCH);
        for (CrawledNotice notice : pending) {
            fetchDetail(source, notice.getUrl()).ifPresentOrElse(
                    content -> persistence.applyContent(notice.getId(), content),
                    () -> persistence.recordMediaAttempt(notice.getId(), now));
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
