package com.moagwa.user;

import com.moagwa.common.exception.ApiException;
import com.moagwa.reminder.ReminderService;
import com.moagwa.user.MeDto.NotificationList;
import com.moagwa.user.MeDto.NotificationSettings;
import com.moagwa.user.MeDto.Profile;
import com.moagwa.user.MeDto.ProfileUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        Long before = u.getPrimaryDepartmentId();
        Long after = req.primaryDepartmentId();
        if (!Objects.equals(before, after)) {
            // 주전공을 고르거나 바꾼 순간에만 관심 학과와 학과 학생회를 맞춤. 그 뒤 학생이 해제한 항목은 그대로 둠
            departments = new ArrayList<>(departments);
            organizations = new ArrayList<>(organizations);
            if (before != null) {
                departments.remove(before);
                organizations.remove(me.studentGovernmentOf(before));
            }
            if (after != null) {
                departments.add(after);
                Long council = me.studentGovernmentOf(after);
                if (council != null) {
                    organizations.add(council);
                }
            }
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
