package com.moagwa.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
            @NotBlank @Email @Size(max = 200) String email,
            @NotBlank @Size(max = 40) String phone,
            @NotBlank @Size(max = 500) String channelUrl,
            @NotBlank @Size(max = 3000) String reason) {
    }

    record Submitted(long id, String status) {
    }

    record ApplicationView(
            long id, String organizationName, String university, String department, String contactName,
            String email, String phone, String channelUrl, String reason, String status, String rejectionReason,
            String createdAt) {
    }

    /** 메일을 보냈으면 setupLink는 null. 메일 설정이 없는 환경에서만 운영자에게 링크를 직접 돌려줌 */
    record Approved(boolean mailSent, String setupLink) {
    }

    record Rejection(@Size(max = 500) String reason) {
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
