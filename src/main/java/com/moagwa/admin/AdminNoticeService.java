package com.moagwa.admin;

import com.moagwa.auth.CurrentUser;
import com.moagwa.common.exception.ApiException;
import com.moagwa.notice.Category;
import com.moagwa.notice.CategoryRepository;
import com.moagwa.notice.DisplayStatus;
import com.moagwa.notice.Notice;
import com.moagwa.notice.NoticeBlock;
import com.moagwa.notice.NoticeBlockCodec;
import com.moagwa.notice.NoticeImage;
import com.moagwa.notice.NoticeRepository;
import com.moagwa.notice.NoticeStatus;
import com.moagwa.notification.NotificationService;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.realtime.NoticeChangedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All operations are scoped to the organization in the caller's token; ids from the client are never trusted. */
@Service
@RequiredArgsConstructor
public class AdminNoticeService {

    private final NoticeRepository notices;
    private final AdminRepository admins;
    private final OrganizationRepository organizations;
    private final CategoryRepository categories;
    private final NotificationService notifications;
    private final NoticeBlockCodec blockCodec;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    private static final int MAX_IMAGES = 10;

    @Transactional(readOnly = true)
    public List<AdminNoticeView> list(CurrentUser me, String tab, String q) {
        Instant now = clock.instant();
        return organizationNotices(me).stream()
                .filter(n -> matchesTab(n, tab, now))
                .filter(n -> q == null || q.isBlank() || n.getTitle().toLowerCase().contains(q.toLowerCase()))
                .sorted(Comparator.comparing(Notice::getUpdatedAt).reversed())
                .map(n -> view(n, now)).toList();
    }

    @Transactional(readOnly = true)
    public AdminNoticeView get(CurrentUser me, long id) {
        return view(owned(me, id), clock.instant());
    }

    @Transactional
    public AdminNoticeView create(CurrentUser me, NoticeRequest req) {
        validate(req);
        Admin author = admins.getReferenceById(me.id());
        Organization org = organizations.getReferenceById(me.organizationId());
        Notice n = new Notice(org, author);
        apply(n, req);
        n = notices.saveAndFlush(n);
        applyMode(n, req.publishMode() == null ? "DRAFT" : req.publishMode(), req.publishAt());
        return view(n, clock.instant());
    }

    @Transactional
    public AdminNoticeView update(CurrentUser me, long id, NoticeRequest req) {
        validate(req);
        Notice n = owned(me, id);
        boolean visible = n.isVisibleToStudents();
        apply(n, req);
        if (req.publishMode() != null) {
            applyMode(n, req.publishMode(), req.publishAt());
        } else if (visible) {
            events.publishEvent(new NoticeChangedEvent(NoticeChangedEvent.UPDATED, "MANUAL", n.getId()));
        }
        return view(n, clock.instant());
    }

    @Transactional
    public AdminNoticeView publish(CurrentUser me, long id, String mode, Instant publishAt) {
        Notice n = owned(me, id);
        applyMode(n, mode, publishAt);
        return view(n, clock.instant());
    }

    @Transactional
    public AdminNoticeView close(CurrentUser me, long id) {
        Notice n = owned(me, id);
        if (n.getStatus() != NoticeStatus.PUBLISHED) {
            throw ApiException.badRequest("게시 중인 공지만 마감할 수 있습니다.");
        }
        n.close();
        events.publishEvent(new NoticeChangedEvent(NoticeChangedEvent.CLOSED, "MANUAL", n.getId()));
        return view(n, clock.instant());
    }

    @Transactional
    public void delete(CurrentUser me, long id) {
        Notice n = owned(me, id);
        boolean visible = n.isVisibleToStudents();
        n.softDelete();
        if (visible) {
            events.publishEvent(new NoticeChangedEvent(NoticeChangedEvent.DELETED, "MANUAL", n.getId()));
        }
    }

    @Transactional
    public AdminNoticeView duplicate(CurrentUser me, long id) {
        Notice src = owned(me, id);
        Notice copy = new Notice(src.getOrganization(), admins.getReferenceById(me.id()));
        String title = src.getTitle().length() > 33 ? src.getTitle().substring(0, 33) : src.getTitle();
        copy.edit(title + " (복사)", src.getDescription(), new HashSet<>(src.getCategories()),
                src.getImages().stream().map(NoticeImage::getUrl).toList(), src.getEventStartAt(),
                src.getEventEndAt(), src.getDeadlineAt(), src.getLocation(), src.getExternalLink(), src.isUrgent(),
                src.getContentBlocks());
        return view(notices.save(copy), clock.instant());
    }

    /** Publishes scheduled notices whose time has come; called by the scheduler. */
    @Transactional
    public int publishDue() {
        List<Notice> due = notices.findDueScheduled(clock.instant());
        due.forEach(n -> markPublished(n, n.getPublishAt()));
        return due.size();
    }

    public record DashboardData(long active, long closingToday, long scheduled, long ended, int notices,
                                long views, long bookmarks, long shares, List<AdminNoticeView> activeNotices) {
    }

    @Transactional(readOnly = true)
    public DashboardData dashboard(CurrentUser me) {
        Instant now = clock.instant();
        java.time.ZoneId kst = java.time.ZoneId.of("Asia/Seoul");
        java.time.LocalDate today = now.atZone(kst).toLocalDate();
        List<Notice> all = organizationNotices(me);
        List<Notice> active = all.stream().filter(n -> n.getStatus() == NoticeStatus.PUBLISHED).filter(n -> {
            DisplayStatus d = n.displayStatus(now);
            return d != DisplayStatus.CLOSED && d != DisplayStatus.ENDED;
        }).toList();
        long closingToday = active.stream().filter(n -> n.getDeadlineAt() != null
                && n.getDeadlineAt().atZone(kst).toLocalDate().equals(today)).count();
        long scheduled = all.stream().filter(n -> n.getStatus() == NoticeStatus.SCHEDULED).count();
        long ended = all.stream().filter(n -> n.getStatus() == NoticeStatus.CLOSED
                || (n.getStatus() == NoticeStatus.PUBLISHED && !active.contains(n))).count();
        return new DashboardData(active.size(), closingToday, scheduled, ended, all.size(),
                all.stream().mapToInt(Notice::getViewCount).sum(),
                all.stream().mapToInt(Notice::getBookmarkCount).sum(),
                all.stream().mapToInt(Notice::getShareCount).sum(),
                active.stream().sorted(Comparator.comparing(Notice::getUpdatedAt).reversed()).limit(5)
                        .map(n -> view(n, now)).toList());
    }

    @Transactional(readOnly = true)
    public List<Notice> organizationNotices(CurrentUser me) {
        return notices.findAllActiveByOrganization(me.organizationId());
    }

    private Notice owned(CurrentUser me, long id) {
        return notices.findActiveByOrganization(id, me.organizationId())
                .orElseThrow(() -> ApiException.notFound("공지를 찾을 수 없습니다."));
    }

    private void apply(Notice n, NoticeRequest req) {
        List<Category> found = categories.findAllById(req.categoryIds());
        if (found.size() != new HashSet<>(req.categoryIds()).size()) {
            throw ApiException.badRequest("존재하지 않는 카테고리가 포함되어 있습니다.");
        }
        String description;
        List<String> images;
        String blocksJson = null;
        if (req.blocks() != null && !req.blocks().isEmpty()) {
            List<NoticeBlock> blocks = normalize(req.blocks());
            description = blocks.stream().filter(b -> NoticeBlock.TEXT.equals(b.type())).map(NoticeBlock::text)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            images = blocks.stream().filter(b -> NoticeBlock.IMAGES.equals(b.type()))
                    .flatMap(b -> b.images().stream()).toList();
            blocksJson = blockCodec.toJson(blocks);
        } else {
            description = req.description() == null ? "" : req.description();
            images = req.imageUrls() == null ? List.of() : req.imageUrls();
        }
        if (description.isBlank()) {
            throw ApiException.badRequest("본문을 입력해 주세요.");
        }
        if (images.size() > MAX_IMAGES) {
            throw ApiException.badRequest("이미지는 공지당 최대 " + MAX_IMAGES + "장까지 넣을 수 있습니다.");
        }
        n.edit(req.title().trim(), description, new HashSet<>(found), images, req.eventStartAt(),
                req.eventEndAt(), req.deadlineAt(), blankToNull(req.location()), blankToNull(req.externalLink()),
                req.urgent(), blocksJson);
    }

    /** Drops empty blocks and fills defaults so stored bodies are always well-formed. */
    private static List<NoticeBlock> normalize(List<BlockInput> input) {
        List<NoticeBlock> out = new java.util.ArrayList<>();
        for (BlockInput b : input) {
            if (NoticeBlock.TEXT.equals(b.type())) {
                if (b.text() != null && !b.text().isBlank()) {
                    out.add(new NoticeBlock(NoticeBlock.TEXT, b.text(), null, null));
                }
            } else if (b.images() != null && !b.images().isEmpty()) {
                out.add(new NoticeBlock(NoticeBlock.IMAGES, null, List.copyOf(b.images()),
                        b.layout() == null ? NoticeBlock.CAROUSEL : b.layout()));
            }
        }
        return out;
    }

    private void applyMode(Notice n, String mode, Instant publishAt) {
        Instant now = clock.instant();
        switch (mode) {
            case "DRAFT" -> {
                if (n.isVisibleToStudents()) {
                    throw ApiException.badRequest("게시된 공지는 임시저장으로 되돌릴 수 없습니다.");
                }
                n.saveAsDraft();
            }
            case "NOW" -> {
                if (n.getStatus() != NoticeStatus.PUBLISHED) {
                    markPublished(n, now);
                } else {
                    events.publishEvent(new NoticeChangedEvent(NoticeChangedEvent.UPDATED, "MANUAL", n.getId()));
                }
            }
            case "SCHEDULED" -> {
                if (publishAt == null || !publishAt.isAfter(now)) {
                    throw ApiException.badRequest("예약 게시 시각은 현재 이후여야 합니다.");
                }
                if (n.isVisibleToStudents()) {
                    throw ApiException.badRequest("이미 게시된 공지는 예약할 수 없습니다.");
                }
                n.schedule(publishAt);
            }
            default -> throw ApiException.badRequest("알 수 없는 게시 방식입니다.");
        }
    }

    private void markPublished(Notice n, Instant at) {
        n.publishNow(at);
        notifications.notifyManual(n.getId(), n.getOrganization().getId(),
                n.getCategories().stream().map(Category::getId).toList(), n.getTitle(), n.isUrgent());
        events.publishEvent(new NoticeChangedEvent(NoticeChangedEvent.CREATED, "MANUAL", n.getId()));
    }

    private void validate(NoticeRequest req) {
        if (req.eventStartAt() != null && req.eventEndAt() != null && !req.eventEndAt().isAfter(req.eventStartAt())) {
            throw ApiException.badRequest("행사 종료 시각은 시작 이후여야 합니다.");
        }
    }

    private static boolean matchesTab(Notice n, String tab, Instant now) {
        if (tab == null || tab.equals("all")) {
            return true;
        }
        DisplayStatus d = n.displayStatus(now);
        boolean ended = n.getStatus() == NoticeStatus.CLOSED || d == DisplayStatus.CLOSED || d == DisplayStatus.ENDED;
        return switch (tab) {
            case "active" -> n.getStatus() == NoticeStatus.PUBLISHED && !ended;
            case "scheduled" -> n.getStatus() == NoticeStatus.SCHEDULED;
            case "closed" -> n.getStatus() != NoticeStatus.DRAFT && n.getStatus() != NoticeStatus.SCHEDULED && ended;
            case "draft" -> n.getStatus() == NoticeStatus.DRAFT;
            default -> true;
        };
    }

    public AdminNoticeView view(Notice n, Instant now) {
        DisplayStatus d = n.displayStatus(now);
        return new AdminNoticeView(n.getId(), n.getTitle(), n.getDescription(),
                n.getCategories().stream().map(Category::getId).sorted().toList(),
                n.getCategories().stream().map(Category::getCode).sorted().toList(),
                n.getImages().stream().map(NoticeImage::getUrl).toList(), blockCodec.fromJson(n.getContentBlocks()),
                n.getStatus().name(),
                d == null ? null : d.name(), n.isUrgent(), n.getPublishAt(), n.getEventStartAt(),
                n.getEventEndAt(), n.getDeadlineAt(), n.getLocation(), n.getExternalLink(), n.getViewCount(),
                n.getBookmarkCount(), n.getShareCount(), n.getCreatedAt(), n.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
