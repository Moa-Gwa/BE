package com.moagwa.crawler.domain;

import com.moagwa.organization.Organization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "crawl_sources")
@Getter
@NoArgsConstructor
public class CrawlSource {

    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(name = "list_url", nullable = false)
    private String listUrl;

    @Column(name = "row_selector", nullable = false)
    private String rowSelector = "table tbody tr";

    @Column(name = "link_selector", nullable = false)
    private String linkSelector = "a.b-title";

    @Column(name = "date_selector", nullable = false)
    private String dateSelector = ".b-date";

    @Column(name = "body_selector", nullable = false)
    private String bodySelector = ".b-con-box";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_crawled_at")
    private Instant lastCrawledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status")
    private CrawlStatus lastStatus;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    public CrawlSource(String code, Organization organization, String listUrl) {
        this.code = code;
        this.organization = organization;
        this.listUrl = listUrl;
    }

    /** True until the first successful crawl; used to suppress notifications for the initial backfill. */
    public boolean isFirstCrawl() {
        return lastCrawledAt == null;
    }

    /** Imported sources are stored for provenance but must not be sent to the HTML board crawler. */
    public void disable() {
        this.enabled = false;
    }

    public void markSuccess(Instant now, boolean empty) {
        this.lastCrawledAt = now;
        this.lastStatus = empty ? CrawlStatus.EMPTY : CrawlStatus.SUCCESS;
        this.lastError = null;
        this.consecutiveFailures = empty ? consecutiveFailures + 1 : 0;
    }

    public void markFailure(String error) {
        this.lastStatus = CrawlStatus.FAILED;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
        this.consecutiveFailures++;
    }
}
