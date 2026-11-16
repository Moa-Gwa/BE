package com.moagwa.crawler;

import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNotice;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.crawler.parser.DetailContent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short transactions around the crawler's database work, so no connection is held during network I/O.
 * Each call is its own transaction (the caller is not transactional).
 */
@Component
@RequiredArgsConstructor
class CrawlPersistence {

    private final CrawlSourceRepository sources;
    private final CrawledNoticeRepository notices;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public Set<String> findExistingExternalIds(Long sourceId, Collection<String> externalIds) {
        return notices.findExistingExternalIds(sourceId, List.copyOf(externalIds));
    }

    @Transactional(readOnly = true)
    public List<CrawledNotice> findUnfetched(Long sourceId, int limit) {
        return notices.findUnfetched(sourceId, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<CrawledNotice> findMissingMedia(Long sourceId, Instant retryBefore, int limit) {
        return notices.findMissingMedia(sourceId, retryBefore, PageRequest.of(0, limit));
    }

    /** Throws DataIntegrityViolationException if (source, externalId) already exists. */
    @Transactional
    public CrawledNotice saveNew(CrawledNotice notice, DetailContent content, boolean notifySubscribers) {
        if (content != null) {
            notice.applyContent(content.html(), content.text());
            notice.assignBoardMedia(content.thumbnailUrl(), content.attachments());
        }
        CrawledNotice saved = notices.saveAndFlush(notice);
        // 커밋 후 리스너가 받도록 트랜잭션 안에서 이벤트 등록
        if (notifySubscribers) {
            var source = saved.getSource();
            events.publishEvent(new CrawledNoticeCreatedEvent(saved.getId(), source.getId(),
                    source.getOrganization().getId(), saved.getTitle()));
        }
        return saved;
    }

    @Transactional
    public int fillMissingCategory(Long sourceId, List<String> externalIds, String category) {
        return externalIds.isEmpty() ? 0 : notices.fillMissingCategory(sourceId, externalIds, category);
    }

    @Transactional
    public void applyContent(Long noticeId, DetailContent content) {
        notices.findById(noticeId).ifPresent(n -> {
            n.applyContent(content.html(), content.text());
            n.assignBoardMedia(content.thumbnailUrl(), content.attachments());
        });
    }

    @Transactional
    public void recordMediaAttempt(Long noticeId, Instant attemptedAt) {
        notices.findById(noticeId).ifPresent(n -> n.markMediaAttempted(attemptedAt));
    }

    @Transactional
    public void recordSuccess(Long sourceId, Instant now, boolean empty) {
        sources.findById(sourceId).ifPresent(s -> s.markSuccess(now, empty));
    }

    @Transactional
    public void recordFailure(Long sourceId, String error) {
        sources.findById(sourceId).ifPresent(s -> s.markFailure(error));
    }
}
