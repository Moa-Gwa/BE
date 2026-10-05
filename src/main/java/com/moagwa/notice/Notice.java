package com.moagwa.notice;

import com.moagwa.admin.Admin;
import com.moagwa.organization.Organization;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notices")
@Getter
@NoArgsConstructor
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id")
    private Admin author;

    @Column(nullable = false, length = 40)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NoticeStatus status = NoticeStatus.DRAFT;

    @Column(name = "publish_at")
    private Instant publishAt;

    @Column(name = "event_start_at")
    private Instant eventStartAt;

    @Column(name = "event_end_at")
    private Instant eventEndAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    private String location;

    @Column(name = "external_link")
    private String externalLink;

    @Column(name = "is_urgent", nullable = false)
    private boolean urgent;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @Column(name = "bookmark_count", nullable = false)
    private int bookmarkCount;

    @Column(name = "share_count", nullable = false)
    private int shareCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToMany
    @JoinTable(name = "notice_categories",
            joinColumns = @JoinColumn(name = "notice_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<Category> categories = new LinkedHashSet<>();

    @OneToMany(mappedBy = "notice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<NoticeImage> images = new ArrayList<>();

    public Notice(Organization organization, Admin author) {
        this.organization = organization;
        this.author = author;
    }

    /** Applies the editable fields; used for create and update. */
    public void edit(String title, String description, Set<Category> categories, List<String> imageUrls,
                     Instant eventStartAt, Instant eventEndAt, Instant deadlineAt, String location,
                     String externalLink, boolean urgent) {
        this.title = title;
        this.description = description;
        this.categories.clear();
        this.categories.addAll(categories);
        this.images.clear();
        for (int i = 0; i < imageUrls.size(); i++) {
            this.images.add(new NoticeImage(this, imageUrls.get(i), i));
        }
        this.eventStartAt = eventStartAt;
        this.eventEndAt = eventEndAt;
        this.deadlineAt = deadlineAt;
        this.location = location;
        this.externalLink = externalLink;
        this.urgent = urgent;
        this.updatedAt = Instant.now();
    }

    public void publishNow(Instant now) {
        this.status = NoticeStatus.PUBLISHED;
        this.publishAt = now;
        this.updatedAt = now;
    }

    public void schedule(Instant at) {
        this.status = NoticeStatus.SCHEDULED;
        this.publishAt = at;
        this.updatedAt = Instant.now();
    }

    public void saveAsDraft() {
        this.status = NoticeStatus.DRAFT;
        this.publishAt = null;
        this.updatedAt = Instant.now();
    }

    public void close() {
        this.status = NoticeStatus.CLOSED;
        this.updatedAt = Instant.now();
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    public boolean isVisibleToStudents() {
        return deletedAt == null && (status == NoticeStatus.PUBLISHED || status == NoticeStatus.CLOSED);
    }

    public DisplayStatus displayStatus(Instant now) {
        return NoticeStatusCalculator.display(status, eventStartAt, eventEndAt, deadlineAt, now);
    }
}
