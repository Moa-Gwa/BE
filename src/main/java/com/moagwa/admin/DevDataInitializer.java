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
 * Local development data only (profile "local"): three student-government organizations with one admin each
 * and a few sample notices. Skipped when student-government organizations already exist.
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
        boolean seeded = organizations.findAll().stream()
                .anyMatch(o -> o.getType() == OrganizationType.STUDENT_GOVERNMENT);
        if (seeded) {
            return;
        }
        createAdmin("student-council", "총학생회 관리자", "총학생회");
        createAdmin("ai-council", "AI융합학부 관리자", "AI융합학부 학생회");
        createAdmin("cs-council", "컴퓨터정보공학부 관리자", "컴퓨터정보공학부 학생회");
        log.info("개발용 관리자 계정을 생성했습니다: student-council / ai-council / cs-council");
    }

    private void createAdmin(String loginId, String name, String orgName) {
        Organization org = new Organization(orgName, OrganizationType.STUDENT_GOVERNMENT);
        org.markVerified();
        org = organizations.save(org);
        admins.save(new Admin(loginId, encoder.encode(PASSWORD), name, org));
    }
}
