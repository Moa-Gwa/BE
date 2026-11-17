package com.moagwa.admin;

import com.moagwa.admin.AdminApplicationDto.ApplicationForm;
import com.moagwa.admin.AdminApplicationDto.ApplicationView;
import com.moagwa.admin.AdminApplicationDto.Approved;
import com.moagwa.admin.AdminApplicationDto.PasswordSetup;
import com.moagwa.admin.AdminApplicationDto.Rejected;
import com.moagwa.admin.AdminApplicationDto.SetupStatus;
import com.moagwa.admin.AdminApplicationDto.Submitted;
import com.moagwa.admin.AdminApplicationRepository.Application;
import com.moagwa.common.exception.ApiException;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단체 관리자 계정 발급 흐름.
 * 신청(누구나) → 운영자가 사실 확인 후 승인 → 담당자가 메일 링크로 비밀번호 설정 → 로그인 가능.
 */
@Service
class AdminApplicationService {

    private static final Duration SETUP_LINK_TTL = Duration.ofHours(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminApplicationRepository applications;
    private final OrganizationRepository organizations;
    private final AdminRepository admins;
    private final PasswordEncoder encoder;
    private final AdminSetupMailer mailer;
    private final String frontendUrl;

    AdminApplicationService(AdminApplicationRepository applications, OrganizationRepository organizations,
                            AdminRepository admins, PasswordEncoder encoder, AdminSetupMailer mailer,
                            @Value("${moagwa.frontend-url}") String frontendUrl) {
        this.applications = applications;
        this.organizations = organizations;
        this.admins = admins;
        this.encoder = encoder;
        this.mailer = mailer;
        this.frontendUrl = validateFrontendUrl(frontendUrl);
    }

    Submitted submit(ApplicationForm form) {
        String email = form.email().toLowerCase(Locale.ROOT);
        if (loginIdTaken(form.loginId())) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
        try {
            return new Submitted(applications.insert(form, email), "PENDING");
        } catch (DuplicateKeyException e) {
            // 위 검사 직후 같은 아이디의 신청이 먼저 저장된 경우
            if (String.valueOf(e.getMessage()).contains("uq_admin_applications_active_login_id")) {
                throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
            }
            throw new ApiException(HttpStatus.CONFLICT, "같은 메일로 접수되었거나 승인된 신청이 이미 있습니다.");
        }
    }

    List<ApplicationView> list(String status) {
        return applications.findByStatus(status);
    }

    @Transactional
    Approved approve(long id) {
        Application a = applications.lock(id).orElseThrow(() -> ApiException.notFound("신청을 찾을 수 없습니다."));
        if (!a.pending()) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 처리된 신청입니다.");
        }
        if (admins.existsByLoginIdIgnoreCase(a.loginId())) {
            throw new ApiException(HttpStatus.CONFLICT, "같은 아이디의 관리자 계정이 이미 있습니다.");
        }
        if (organizations.findByName(a.organizationName()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "같은 이름의 조직이 이미 있습니다.");
        }

        Organization org;
        Admin admin;
        try {
            org = organizations.save(verifiedOrganization(a));
            // 비밀번호는 담당자가 설정 링크로 정하기 전까지 아무도 모르는 값으로 둠
            admin = admins.save(Admin.awaitingSetup(a.loginId(), encoder.encode(UUID.randomUUID().toString()),
                    a.contactName(), org));
        } catch (DataIntegrityViolationException e) {
            // 위 검사 직후 다른 승인이 같은 조직 이름이나 아이디를 먼저 저장한 경우. 트랜잭션은 롤백됨
            throw new ApiException(HttpStatus.CONFLICT, "같은 조직 이름이나 아이디로 방금 승인된 신청이 있습니다.");
        }

        applications.markApproved(id, org.getId(), admin.getId());
        return issueSetupLink(id, a.email());
    }

    /** 설정 링크가 만료됐거나 메일을 못 받은 경우, 아직 비밀번호를 정하지 않은 계정에 링크를 새로 보냄 */
    @Transactional
    Approved resendSetupLink(long id) {
        Application a = applications.lock(id).orElseThrow(() -> ApiException.notFound("신청을 찾을 수 없습니다."));
        if (!a.approved()) {
            throw new ApiException(HttpStatus.CONFLICT, "승인된 신청에만 설정 링크를 다시 보낼 수 있습니다.");
        }
        if (admins.findById(a.adminId()).orElseThrow().isActivated()) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 비밀번호를 설정한 계정입니다.");
        }
        return issueSetupLink(id, a.email());
    }

    /** 새 토큰을 저장하면 이전 링크는 더 이상 쓸 수 없음 */
    private Approved issueSetupLink(long applicationId, String email) {
        String token = newToken();
        applications.saveSetupToken(applicationId, sha256(token), Instant.now().plus(SETUP_LINK_TTL));
        String link = frontendUrl + "/admin-setup?token=" + token;
        boolean mailSent = mailer.send(email, link);
        return new Approved(mailSent, mailSent ? null : link);
    }

    private boolean loginIdTaken(String loginId) {
        return admins.existsByLoginIdIgnoreCase(loginId) || applications.activeLoginIdExists(loginId);
    }

    @Transactional
    Rejected reject(long id, String reason) {
        var email = applications.rejectPending(id, reason);
        if (email.isPresent()) {
            return new Rejected(mailer.sendRejection(email.get(), reason));
        }
        if (applications.exists(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 처리된 신청입니다.");
        }
        throw ApiException.notFound("신청을 찾을 수 없습니다.");
    }

    SetupStatus checkSetupLink(String token) {
        return applications.findSetup(sha256(token))
                .map(t -> new SetupStatus(t.email(), t.expiresAt().isAfter(Instant.now())))
                .orElseThrow(() -> ApiException.notFound("유효하지 않은 설정 링크입니다."));
    }

    @Transactional
    void setupPassword(PasswordSetup req) {
        long adminId = applications.consumeSetupToken(sha256(req.token()))
                .orElseThrow(() -> ApiException.badRequest("설정 링크가 만료되었거나 이미 사용되었습니다."));
        admins.findById(adminId).orElseThrow().activate(encoder.encode(req.password()));
    }

    private static String validateFrontendUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("moagwa.frontend-url must not be blank");
        }

        try {
            var uri = java.net.URI.create(value.trim());
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || !java.util.Set.of("http", "https").contains(uri.getScheme())) {
                throw new IllegalStateException("moagwa.frontend-url must be an absolute HTTP(S) URL");
            }
            return value.trim().replaceAll("/+$", "");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("moagwa.frontend-url is invalid", e);
        }
    }

    private static Organization verifiedOrganization(Application a) {
        Organization org = new Organization(a.organizationName(), OrganizationType.STUDENT_GOVERNMENT);
        org.markVerified();
        org.updateInfo("대학: " + a.university() + " · " + a.department(), null, a.channelUrl());
        return org;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 설정 토큰은 DB에 해시로만 저장함 */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
