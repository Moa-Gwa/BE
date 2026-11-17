package com.moagwa.user;

import com.moagwa.auth.JwtService;
import com.moagwa.common.exception.ApiException;
import com.moagwa.reminder.ReminderService;
import com.moagwa.user.MeDto.ConsentRequest;
import com.moagwa.user.MeDto.ConsentStatus;
import com.moagwa.user.MeDto.NotificationList;
import com.moagwa.user.MeDto.NotificationSettings;
import com.moagwa.user.MeDto.Profile;
import com.moagwa.user.MeDto.ProfileUpdate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 학생 본인의 프로필·관심사, 공지 저장, 알림. 사용자 id는 항상 토큰에서 옴. */
@Service
@RequiredArgsConstructor
class MeService {

    private final UserRepository users;
    private final MeRepository me;
    private final ReminderService reminders;
    private final JwtService jwt;
    private final ConsentRepository consents;

    @Transactional(readOnly = true)
    Profile profile(long userId) {
        return profileOf(user(userId));
    }

    @Transactional
    Profile updateProfile(long userId, ProfileUpdate req) {
        User u = user(userId);
        List<Long> departments = distinct(req.departmentIds());
        List<Long> organizations = distinct(req.organizationIds());
        List<Long> categories = distinct(req.categoryIds());
        if (req.primaryDepartmentId() != null) {
            requireOrganizations(List.of(req.primaryDepartmentId()), "DEPARTMENT", "주전공");
        }
        requireOrganizations(departments, "DEPARTMENT", "관심 학과");
        requireOrganizations(organizations, "STUDENT_GOVERNMENT", "관심 학생자치단체");
        if (me.countCategories(categories) != categories.size()) {
            throw ApiException.badRequest("존재하지 않는 카테고리가 포함되어 있습니다.");
        }
        u.updateProfile(req.nickname(), req.primaryDepartmentId(), req.admissionYear());
        me.deleteInterests(userId);
        me.insertInterests(userId, "DEPARTMENT", departments);
        me.insertInterests(userId, "ORGANIZATION", organizations);
        me.insertInterests(userId, "CATEGORY", categories);
        return profileOf(u);
    }

    /** 직접 등록 공지만 notices.bookmark_count를 함께 갱신함 */
    @Transactional
    void bookmark(long userId, String source, long noticeId) {
        String s = source(source);
        if (me.insertBookmark(userId, s, noticeId) && s.equals("MANUAL")) {
            me.addNoticeBookmarkCount(noticeId, 1);
        }
    }

    @Transactional
    void unbookmark(long userId, String source, long noticeId) {
        String s = source(source);
        reminders.remove(userId, s, noticeId);
        if (me.deleteBookmark(userId, s, noticeId) && s.equals("MANUAL")) {
            me.addNoticeBookmarkCount(noticeId, -1);
        }
    }

    @Transactional(readOnly = true)
    NotificationList notifications(long userId) {
        return new NotificationList(me.countUnread(userId), me.recentNotifications(userId));
    }

    void markRead(long userId, long notificationId) {
        me.markRead(userId, notificationId);
    }

    void markAllRead(long userId) {
        me.markAllRead(userId);
    }

    NotificationSettings settings(long userId) {
        return me.findSettings(userId).orElse(NotificationSettings.DEFAULT);
    }

    NotificationSettings updateSettings(long userId, NotificationSettings s) {
        me.saveSettings(userId, s);
        return s;
    }

    @Transactional(readOnly = true)
    ConsentStatus consent(long userId) {
        boolean agreed = consents.hasCurrentConsent(userId);
        return new ConsentStatus(!agreed, User.TERMS_VERSION, agreed ? User.TERMS_VERSION : null);
    }

    /** 동의를 저장하고 동의가 반영된 액세스 토큰을 돌려줌 */
    @Transactional
    String agree(long userId, ConsentRequest req) {
        if (!User.TERMS_VERSION.equals(req.version())) {
            throw new ApiException(HttpStatus.CONFLICT, "약관이 바뀌었습니다. 새로고침 후 다시 동의해 주세요.");
        }
        User u = user(userId);
        consents.agreeCurrent(userId);
        return jwt.issueStudent(u, true);
    }

    @Transactional
    void withdraw(long userId) {
        users.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
        if (me.hasOngoingTrade(userId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "진행 중인 거래나 모집 중인 같이 사기가 있어 탈퇴할 수 없습니다. 먼저 정리해 주세요.");
        }
        me.deleteUser(userId);
    }

    private User user(long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
    }

    private Profile profileOf(User u) {
        return new Profile(u.getId(), u.getNickname(), u.getPrimaryDepartmentId(), u.getAdmissionYear(),
                me.interests(u.getId(), "DEPARTMENT"), me.interests(u.getId(), "ORGANIZATION"),
                me.interests(u.getId(), "CATEGORY"));
    }

    private void requireOrganizations(List<Long> ids, String type, String label) {
        if (!ids.isEmpty() && me.countOrganizations(ids, type) != ids.size()) {
            throw ApiException.badRequest(label + " 선택이 올바르지 않습니다.");
        }
    }

    private static String source(String s) {
        String upper = s.toUpperCase();
        if (!upper.equals("MANUAL") && !upper.equals("CRAWLED")) {
            throw ApiException.badRequest("알 수 없는 공지 출처입니다.");
        }
        return upper;
    }

    private static List<Long> distinct(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().distinct().toList();
    }
}
