package com.moagwa.admin;

import com.moagwa.auth.CurrentUser;
import com.moagwa.notice.DisplayStatus;
import com.moagwa.notice.Notice;
import com.moagwa.notice.NoticeStatus;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
class AdminController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AdminNoticeService service;
    private final UploadService uploads;
    private final OrganizationRepository organizations;
    private final Clock clock;

    @GetMapping("/notices")
    List<AdminNoticeView> list(@RequestParam(defaultValue = "all") String tab,
                               @RequestParam(required = false) String q, Authentication auth) {
        return service.list(CurrentUser.from(auth), tab, q);
    }

    @GetMapping("/notices/{id}")
    AdminNoticeView get(@PathVariable long id, Authentication auth) {
        return service.get(CurrentUser.from(auth), id);
    }

    @PostMapping("/notices")
    @ResponseStatus(HttpStatus.CREATED)
    AdminNoticeView create(@Valid @RequestBody NoticeRequest req, Authentication auth) {
        return service.create(CurrentUser.from(auth), req);
    }

    @PutMapping("/notices/{id}")
    AdminNoticeView update(@PathVariable long id, @Valid @RequestBody NoticeRequest req, Authentication auth) {
        return service.update(CurrentUser.from(auth), id, req);
    }

    @DeleteMapping("/notices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id, Authentication auth) {
        service.delete(CurrentUser.from(auth), id);
    }

    @PostMapping("/notices/{id}/publish")
    AdminNoticeView publish(@PathVariable long id, @RequestBody PublishRequest req, Authentication auth) {
        return service.publish(CurrentUser.from(auth), id, req.mode(), req.publishAt());
    }

    @PostMapping("/notices/{id}/close")
    AdminNoticeView close(@PathVariable long id, Authentication auth) {
        return service.close(CurrentUser.from(auth), id);
    }

    @PostMapping("/notices/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    AdminNoticeView duplicate(@PathVariable long id, Authentication auth) {
        return service.duplicate(CurrentUser.from(auth), id);
    }

    @PostMapping(value = "/uploads/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, List<String>> upload(@RequestParam("files") List<MultipartFile> files) {
        return Map.of("urls", uploads.store(files));
    }

    @GetMapping("/dashboard")
    Dashboard dashboard(Authentication auth) {
        CurrentUser me = CurrentUser.from(auth);
        AdminNoticeService.DashboardData d = service.dashboard(me);
        Organization org = organizations.findById(me.organizationId()).orElseThrow();
        return new Dashboard(
                new OrgSummary(org.getId(), org.getName(), org.getType().name()),
                new Kpi(d.active(), d.closingToday(), d.scheduled(), d.ended()),
                new Performance(d.notices(), d.views(), d.bookmarks(), d.shares()),
                d.activeNotices());
    }

    @GetMapping("/schedule")
    List<ScheduleItem> schedule(Authentication auth) {
        CurrentUser me = CurrentUser.from(auth);
        List<ScheduleItem> items = new ArrayList<>();
        for (Notice n : service.organizationNotices(me)) {
            if (n.getStatus() == NoticeStatus.SCHEDULED && n.getPublishAt() != null) {
                items.add(new ScheduleItem("PUBLISH", n.getPublishAt(), n.getId(), n.getTitle()));
            }
            if (n.getStatus() != NoticeStatus.DRAFT) {
                if (n.getEventStartAt() != null) {
                    items.add(new ScheduleItem("EVENT", n.getEventStartAt(), n.getId(), n.getTitle()));
                }
                if (n.getDeadlineAt() != null) {
                    items.add(new ScheduleItem("DEADLINE", n.getDeadlineAt(), n.getId(), n.getTitle()));
                }
            }
        }
        items.sort(Comparator.comparing(ScheduleItem::at));
        return items;
    }

    @GetMapping("/organization")
    OrgInfo organization(Authentication auth) {
        return orgInfo(organizations.findById(CurrentUser.from(auth).organizationId()).orElseThrow());
    }

    @PutMapping("/organization")
    @Transactional
    OrgInfo updateOrganization(@Valid @RequestBody OrgUpdate req, Authentication auth) {
        Organization org = organizations.findById(CurrentUser.from(auth).organizationId()).orElseThrow();
        org.updateInfo(req.description(), req.profileImage(), req.externalUrl());
        return orgInfo(org);
    }

    private static OrgInfo orgInfo(Organization o) {
        return new OrgInfo(o.getId(), o.getName(), o.getType().name(), o.getDescription(), o.getProfileImage(),
                o.getExternalUrl(), o.isVerified());
    }

    record PublishRequest(String mode, Instant publishAt) {
    }

    record OrgUpdate(@Size(max = 2000) String description,
                     @Pattern(regexp = "^$|^(/uploads/[A-Za-z0-9._-]+|https://\\S+)$") String profileImage,
                     @Pattern(regexp = "^$|^https?://\\S+$") String externalUrl) {
    }

    record OrgInfo(long id, String name, String type, String description, String profileImage, String externalUrl,
                   boolean verified) {
    }

    record OrgSummary(long id, String name, String type) {
    }

    record Kpi(long active, long closingToday, long scheduled, long ended) {
    }

    record Performance(long notices, long views, long bookmarks, long shares) {
    }

    record Dashboard(OrgSummary organization, Kpi kpi, Performance performance, List<AdminNoticeView> activeNotices) {
    }

    record ScheduleItem(String type, Instant at, long noticeId, String title) {
    }
}
