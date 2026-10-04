package com.moagwa.crawler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "crawled_notices",
        uniqueConstraints = @UniqueConstraint(name = "uq_crawled_notices_source_external",
                columnNames = {"source_id", "external_id"}))
@Getter
@NoArgsConstructor
public class CrawledNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id")
    private CrawlSource source;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(name = "content_html", columnDefinition = "text")
    private String contentHtml;

    @Column(name = "content_text", columnDefinition = "text")
    private String contentText;

    @Column(name = "content_fetched", nullable = false)
    private boolean contentFetched;

    @Column(nullable = false)
    private boolean pinned;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "crawled_at", nullable = false)
    private Instant crawledAt;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @Column(length = 30)
    private String category;

    public CrawledNotice(CrawlSource source, String externalId, String title, String url,
                         boolean pinned, Instant publishedAt, Instant crawledAt) {
        this.source = source;
        this.externalId = externalId;
        this.title = title;
        this.url = url;
        this.pinned = pinned;
        this.publishedAt = publishedAt;
        this.crawledAt = crawledAt;
    }

    public void applyContent(String html, String text) {
        this.contentHtml = html;
        this.contentText = text;
        this.contentFetched = true;
    }
}
