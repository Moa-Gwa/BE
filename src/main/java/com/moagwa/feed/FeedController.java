package com.moagwa.feed;

import com.moagwa.auth.CurrentUser;
import com.moagwa.common.exception.ApiException;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.notice.CategoryRepository;
import java.time.LocalDate;
import java.util.List;
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

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class FeedController {

    private final FeedRepository feed;
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
    void viewNotice(@PathVariable long id) {
        notices.increaseView("MANUAL", id);
    }

    @PostMapping("/crawled-notices/{id}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void viewCrawled(@PathVariable long id) {
        notices.increaseView("CRAWLED", id);
    }

    @GetMapping("/categories")
    List<CategoryView> categories() {
        return categories.findAllByOrderBySortOrder().stream()
                .map(c -> new CategoryView(c.getId(), c.getCode(), c.getName())).toList();
    }

    @GetMapping("/organizations")
    List<OrganizationView> organizations(@RequestParam(required = false) String type,
                                         @RequestParam(required = false) String q) {
        return organizations.findAll().stream()
                .filter(o -> type == null || o.getType().name().equals(type))
                .filter(o -> q == null || o.getName().contains(q))
                .map(o -> new OrganizationView(o.getId(), o.getName(), o.getType().name(), o.getCollege()))
                .sorted(java.util.Comparator.comparing(OrganizationView::name))
                .toList();
    }

    @GetMapping("/schedule")
    List<ScheduleEntry> schedule(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        if (to.isBefore(from) || from.plusDays(62).isBefore(to)) {
            throw ApiException.badRequest("조회 기간은 최대 62일입니다.");
        }
        return feed.schedule(from, to.plusDays(1));
    }

    record CategoryView(long id, String code, String name) {
    }

    record OrganizationView(long id, String name, String type, String college) {
    }
}
