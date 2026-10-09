package com.moagwa.crawler.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @ElementCollection
    @CollectionTable(name = "crawled_notice_images", joinColumns = @JoinColumn(name = "notice_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "url", nullable = false, length = 500)
    private List<String> imageUrls = new ArrayList<>();

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

    /** Sets the category from the board's own label; never overwrites one that is already set. */
    public void assignCategory(String code) {
        if (code != null && this.category == null) {
            this.category = code;
        }
    }

    /** Replaces the post images; the first one doubles as the feed thumbnail. */
    public void assignImages(List<String> urls) {
        this.imageUrls.clear();
        this.imageUrls.addAll(urls);
        this.thumbnailUrl = urls.isEmpty() ? null : urls.get(0);
    }

    /** Replaces the category unconditionally; for curated snapshots that are the source of truth. */
    public void changeCategory(String code) {
        this.category = code;
    }

    public void applyContent(String html, String text) {
        this.contentHtml = html;
        this.contentText = text;
        this.contentFetched = true;
    }
}
