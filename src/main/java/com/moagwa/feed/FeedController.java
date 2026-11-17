package com.moagwa.feed;

import com.moagwa.auth.CurrentUser;
import com.moagwa.common.exception.ApiException;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import com.moagwa.notice.CategoryRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "공지 피드", description = "조회는 누구나. /api/feed/for-me, /api/me/bookmarks는 학생 로그인 필요")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class FeedController {

    private final FeedRepository feed;
    private final AcademicCalendar academicCalendar;
    private final ScheduleColors scheduleColors;
    private final NoticeQueryService notices;
    private final OrganizationRepository organizations;
    private final CategoryRepository categories;

    @GetMapping("/feed")
    FeedPage feed(@RequestParam(required = false) String orgType,
                  @RequestParam(required = false) Long organizationId,
                  @RequestParam(required = false) String category,
                  @RequestParam(required = false) String status,
                  @RequestParam(required = false) String q,
                  @RequestParam(required = false) String sort,
                  @RequestParam(defaultValue = "0") int page,
                  @RequestParam(defaultValue = "20") int size,
                  Authentication auth) {
        return feed.search(new FeedFilter(orgType, organizationId, category, status, q, sort, null,
                CurrentUser.studentIdOrNull(auth), page, size));
    }

    @GetMapping("/feed/now")
    List<FeedItem> now(Authentication auth) {
        return feed.now(CurrentUser.studentIdOrNull(auth), 10);
    }

    @GetMapping("/feed/for-me")
    FeedPage forMe(@RequestParam(defaultValue = "0") int page,
                   @RequestParam(defaultValue = "20") int size,
                   Authentication auth) {
        Long me = CurrentUser.studentIdOrNull(auth);
        if (me == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return feed.search(new FeedFilter(null, null, null, null, null, "latest", me, me, page, size));
    }

    @GetMapping("/me/bookmarks")
    List<FeedItem> bookmarks(@RequestParam(required = false) String status, Authentication auth) {
        return feed.bookmarked(CurrentUser.studentIdOrNull(auth), status);
    }

    @GetMapping("/notices/{id}")
    NoticeDetail notice(@PathVariable long id, Authentication auth) {
        return notices.manual(id, CurrentUser.studentIdOrNull(auth));
    }

    @GetMapping("/crawled-notices/{id}")
    NoticeDetail crawled(@PathVariable long id, Authentication auth) {
        return notices.crawled(id, CurrentUser.studentIdOrNull(auth));
    }

    @PostMapping("/notices/{id}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void viewNotice(@PathVariable long id, Authentication auth) {
        notices.increaseView("MANUAL", id, CurrentUser.studentIdOrNull(auth));
    }

    @PostMapping("/crawled-notices/{id}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void viewCrawled(@PathVariable long id, Authentication auth) {
        notices.increaseView("CRAWLED", id, CurrentUser.studentIdOrNull(auth));
    }

    @GetMapping("/categories")
    List<CategoryView> categories() {
        return categories.findAllByOrderBySortOrder().stream()
                .map(c -> new CategoryView(c.getId(), c.getCode(), c.getName())).toList();
    }

    @GetMapping("/organizations")
    List<OrganizationView> organizations(@RequestParam(required = false) String type,
                                         @RequestParam(required = false) String q) {
        List<Organization> all = organizations.findAll();
        Map<String, Long> councils = all.stream().filter(o -> o.getType() == OrganizationType.STUDENT_GOVERNMENT)
                .collect(Collectors.toMap(Organization::getName, Organization::getId));
        return all.stream()
                .filter(o -> type == null || o.getType().name().equals(type))
                .filter(o -> q == null || o.getName().contains(q))
                .map(o -> new OrganizationView(o.getId(), o.getName(), o.getType().name(), o.getCollege(), o.isRequired(),
                        o.getType() == OrganizationType.DEPARTMENT ? councils.get(Organization.councilNameOf(o.getName())) : null))
                .sorted(java.util.Comparator.comparing(OrganizationView::name))
                .toList();
    }

    @GetMapping("/schedule")
    List<ScheduleEntry> schedule(@RequestParam LocalDate from, @RequestParam LocalDate to, Authentication auth) {
        if (to.isBefore(from) || from.plusDays(62).isBefore(to)) {
            throw ApiException.badRequest("조회 기간은 최대 62일입니다.");
        }
        var entries = new java.util.ArrayList<>(feed.schedule(from, to.plusDays(1), CurrentUser.studentIdOrNull(auth)));
        entries.addAll(academicCalendar.entries(from, to.plusDays(1)));
        entries.sort(java.util.Comparator.comparing(ScheduleEntry::at));
        return scheduleColors.apply(CurrentUser.studentIdOrNull(auth), entries);
    }

    record CategoryView(long id, String code, String name) {
    }

    /** required가 true인 단체는 구독 해제할 수 없어 FE가 해제 불가로 표시함. studentGovernmentId는 학과의 학과 학생회(없으면 null) */
    record OrganizationView(long id, String name, String type, String college, boolean required, Long studentGovernmentId) {
    }
}
