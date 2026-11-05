package com.moagwa.admin;

import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Local development data only (profile "local"): one admin for each of three student-government organizations.
 * Accounts that already exist are left alone, and organizations created by other seed data are reused.
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
class DevDataInitializer implements CommandLineRunner {

    static final String PASSWORD = "moagwa1234";

    private final OrganizationRepository organizations;
    private final AdminRepository admins;
    private final PasswordEncoder encoder;

    @Override
    public void run(String... args) {
        createAdminIfMissing("student-council", "총학생회 관리자", "총학생회");
        createAdminIfMissing("ai-council", "AI융합학부 관리자", "AI융합학부 학생회");
        createAdminIfMissing("cs-council", "컴퓨터정보공학부 관리자", "컴퓨터정보공학부 학생회");
    }

    private void createAdminIfMissing(String loginId, String name, String orgName) {
        if (admins.findByLoginId(loginId).isPresent()) {
            return;
        }
        Organization org = organizations.findByName(orgName).orElseGet(() -> createOrganization(orgName));
        admins.save(new Admin(loginId, encoder.encode(PASSWORD), name, org));
        log.info("개발용 관리자 계정을 생성했습니다: {}", loginId);
    }

    private Organization createOrganization(String orgName) {
        Organization org = new Organization(orgName, OrganizationType.STUDENT_GOVERNMENT);
        org.markVerified();
        return organizations.save(org);
    }
}
