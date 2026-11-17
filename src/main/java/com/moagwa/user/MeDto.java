package com.moagwa.user;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 내 정보 API의 요청과 응답 모양. */
final class MeDto {

    private MeDto() {
    }

    /** required = 현재 버전 약관에 아직 동의하지 않음 */
    record ConsentStatus(boolean required, String version, String agreedVersion) {
    }

    /** 이용약관과 개인정보 수집/이용 모두 필수 동의. version은 화면에 보여 준 문서 버전 */
    record ConsentRequest(@NotBlank String version,
            @AssertTrue(message = "이용약관에 동의해 주세요.") boolean terms,
            @AssertTrue(message = "개인정보 수집, 이용에 동의해 주세요.") boolean privacy) {
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
