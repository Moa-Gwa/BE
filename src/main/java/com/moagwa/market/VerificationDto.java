package com.moagwa.market;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 재학생 인증 API의 요청과 응답 모양. */
final class VerificationDto {

    private VerificationDto() {
    }

    record EmailRequest(@NotBlank @Size(max = 200) String email) {
    }

    record ConfirmRequest(@NotBlank @Pattern(regexp = "^\\d{6}$", message = "6자리 숫자여야 합니다.") String code) {
    }

    record Status(boolean verified, String verifiedAt, String schoolEmail) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SendResult(int expiresInSeconds, String devCode) {
    }

    record ConfirmResult(boolean verified, String token) {
    }
}
