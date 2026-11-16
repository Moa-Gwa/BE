package com.moagwa.admin;

import com.moagwa.admin.AdminApplicationDto.ApplicationForm;
import com.moagwa.admin.AdminApplicationDto.ApplicationView;
import com.moagwa.admin.AdminApplicationDto.Approved;
import com.moagwa.admin.AdminApplicationDto.PasswordSetup;
import com.moagwa.admin.AdminApplicationDto.Rejected;
import com.moagwa.admin.AdminApplicationDto.Rejection;
import com.moagwa.admin.AdminApplicationDto.SetupStatus;
import com.moagwa.admin.AdminApplicationDto.SetupToken;
import com.moagwa.admin.AdminApplicationDto.Submitted;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "단체 관리자 신청", description = "신청·비밀번호 설정은 누구나, 목록·승인·거절은 운영자 전용")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class AdminApplicationController {

    private final AdminApplicationService service;

    @PostMapping("/admin-applications")
    @ResponseStatus(HttpStatus.CREATED)
    Submitted submit(@Valid @RequestBody ApplicationForm form) {
        return service.submit(form);
    }

    @GetMapping("/admin/applications")
    List<ApplicationView> list(@RequestParam(defaultValue = "PENDING") String status) {
        return service.list(status);
    }

    @PostMapping("/admin/applications/{id}/approve")
    Approved approve(@PathVariable long id) {
        return service.approve(id);
    }

    @PostMapping("/admin/applications/{id}/resend-setup")
    Approved resendSetupLink(@PathVariable long id) {
        return service.resendSetupLink(id);
    }

    @PostMapping("/admin/applications/{id}/reject")
    Rejected reject(@PathVariable long id, @Valid @RequestBody(required = false) Rejection body) {
        return service.reject(id, body == null ? null : body.reason());
    }

    @PostMapping("/admin/auth/setup/validate")
    SetupStatus validateSetupLink(@Valid @RequestBody SetupToken body) {
        return service.checkSetupLink(body.token());
    }

    @PostMapping("/admin/auth/setup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setupPassword(@Valid @RequestBody PasswordSetup body) {
        service.setupPassword(body);
    }
}
