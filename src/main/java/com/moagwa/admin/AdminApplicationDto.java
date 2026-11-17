package com.moagwa.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 단체 관리자 계정 신청·승인·비밀번호 설정 API의 요청과 응답 */
final class AdminApplicationDto {

    private AdminApplicationDto() {
    }

    record ApplicationForm(
            @NotBlank @Size(max = 100) String organizationName,
            @NotBlank @Size(max = 100) String university,
            @NotBlank @Size(max = 100) String department,
            @NotBlank @Size(max = 50) String contactName,
            @NotBlank @Email @Size(max = 200)
            @Pattern(regexp = "(?i)[^@\\s]+@catholic\\.ac\\.kr", message = "학교 메일(@catholic.ac.kr)로 신청해 주세요.")
            String email,
            @NotBlank
            @Pattern(regexp = "0\\d{1,2}-?\\d{3,4}-?\\d{4}", message = "전화번호 형식이 올바르지 않습니다. 예: 010-1234-5678")
            String phone,
            @NotBlank @Size(max = 500)
            @Pattern(regexp = "(?i)https?://[^\\s/?#]+\\S*", message = "채널 주소는 http:// 또는 https://로 시작해야 합니다.")
            String channelUrl,
            @NotBlank @Size(max = 3000) String reason,
            @NotBlank
            @Pattern(regexp = "[a-z0-9_-]{4,30}", message = "아이디는 영문 소문자, 숫자, -, _로 4자 이상 30자 이하로 입력해 주세요.")
            String loginId) {
    }

    record Submitted(long id, String status) {
    }

    record ApplicationView(
            long id, String organizationName, String university, String department, String contactName,
            String email, String loginId, String phone, String channelUrl, String reason, String status, String rejectionReason,
            String createdAt) {
    }

    /** 메일을 보냈으면 setupLink는 null. 메일 설정이 없는 환경에서만 운영자에게 링크를 직접 돌려줌 */
    record Approved(boolean mailSent, String setupLink) {
    }

    record Rejection(@Size(max = 500) String reason) {
    }

    /** 승인 시 단체 이름을 고칠 때만 보냄(예: 학과 학생회를 "학과명 학생회" 형식으로 맞춰 학과와 연결) */
    record Approval(@Size(max = 100) String organizationName) {
    }

    /** 반려 안내 메일을 못 보내도 반려는 처리됨 */
    record Rejected(boolean mailSent) {
    }

    record SetupToken(@NotBlank String token) {
    }

    record SetupStatus(String email, boolean valid) {
    }

    record PasswordSetup(
            @NotBlank String token,
            @NotBlank @Size(min = 10, max = 100, message = "비밀번호는 10자 이상 100자 이하로 입력해 주세요.")
            String password) {
    }
}
