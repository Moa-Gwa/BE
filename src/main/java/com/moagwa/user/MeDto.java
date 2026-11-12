package com.moagwa.user;

import jakarta.validation.constraints.Size;
import java.util.List;

/** 내 정보 API의 요청과 응답 모양. */
final class MeDto {

    private MeDto() {
    }

    record Profile(long id, String nickname, Long primaryDepartmentId, Integer admissionYear,
                   List<Long> departmentIds, List<Long> organizationIds, List<Long> categoryIds) {
    }

    record ProfileUpdate(@Size(max = 50) String nickname, Long primaryDepartmentId, Integer admissionYear,
                         List<Long> departmentIds, List<Long> organizationIds, List<Long> categoryIds) {
    }

    record NotificationView(long id, String type, String source, long noticeId, String title, String body,
                            boolean read, String createdAt) {
    }

    record NotificationList(int unreadCount, List<NotificationView> items) {
    }

    record NotificationSettings(boolean newNotice, boolean eventStart, boolean deadlineSoon, boolean urgent) {

        static final NotificationSettings DEFAULT = new NotificationSettings(true, true, true, true);
    }
}
