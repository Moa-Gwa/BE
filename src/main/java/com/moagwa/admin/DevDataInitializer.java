package com.moagwa.admin;

import com.moagwa.auth.CurrentUser;
import com.moagwa.notice.Category;
import com.moagwa.notice.CategoryRepository;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    private final CategoryRepository categories;
    private final AdminNoticeService notices;
    private final PasswordEncoder encoder;

    @Override
    public void run(String... args) {
        boolean seeded = organizations.findAll().stream()
                .anyMatch(o -> o.getType() == OrganizationType.STUDENT_GOVERNMENT);
        if (seeded) {
            return;
        }
        Map<String, Long> cat = categories.findAll().stream().collect(Collectors.toMap(Category::getCode, Category::getId));
        CurrentUser council = createAdmin("student-council", "총학생회 관리자", "총학생회");
        CurrentUser ai = createAdmin("ai-council", "AI융합학부 관리자", "AI융합학부 학생회");
        createAdmin("cs-council", "컴퓨터정보공학부 관리자", "컴퓨터정보공학부 학생회");
        Instant now = Instant.now();

        notices.create(council, req("2026 가을 대동제 부스 운영팀 모집",
                "가을 대동제를 함께 만들어 갈 부스 운영팀을 모집합니다.\n학과·동아리·개인 팀 모두 신청 가능합니다.\n\n"
                        + "행사일: 대동제 기간\n신청: 학생활동 포털 내 신청서 제출",
                List.of(cat.get("EVENT"), cat.get("RECRUIT")), now.plus(Duration.ofDays(25)),
                now.plus(Duration.ofDays(27)), now.plus(Duration.ofDays(7)), "학생회관 앞 광장", false, "NOW", null));
        notices.create(ai, req("[AI융합학부] 간식사업 신청자 모집",
                "중간고사 기간을 맞아 AI융합학부 재학생을 위한 간식사업을 진행합니다.\n\n수령 장소: 1층 로비\n"
                        + "수량이 한정되어 조기 마감될 수 있습니다.",
                List.of(cat.get("SNACK"), cat.get("WELFARE")), now.plus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(1).plusHours(3)), now.plus(Duration.ofHours(5)), "1층 로비", false, "NOW",
                null));
        notices.create(council, req("[중요] 중간고사 기간 도서관 운영 변경",
                "중간고사 기간 동안 열람실 운영 시간이 변경됩니다.\n\n24시간 열람실: 본관 3층",
                List.of(cat.get("ACADEMIC")), now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(10)), null,
                "중앙도서관", true, "NOW", null));
        notices.create(council, req("2학기 학생회비 사용 내역 공개",
                "2학기 학생회비 사용 내역을 투명하게 공개합니다.\n문의는 총학생회 사무실로 연락해 주세요.",
                List.of(cat.get("STUDENT_FEE")), null, null, null, null, false, "NOW", null));
        notices.create(ai, req("제휴 카페 학생 할인 안내",
                "학생증 제시 시 음료 10% 할인을 받을 수 있습니다.", List.of(cat.get("PARTNERSHIP")),
                null, null, now.plus(Duration.ofDays(30)), "정문 앞 카페", false, "NOW", null));
        notices.create(ai, req("[예약] 학과 MT 참가 신청 안내",
                "다음 주 공개되는 학과 MT 참가 신청 안내입니다.", List.of(cat.get("EVENT")),
                now.plus(Duration.ofDays(20)), now.plus(Duration.ofDays(21)), now.plus(Duration.ofDays(8)), "교외",
                false, "SCHEDULED", now.plus(Duration.ofDays(1))));
        notices.create(council, req("[임시저장] 겨울방학 해외봉사단 설명회", "초안입니다.",
                List.of(cat.get("RECRUIT")), null, null, null, null, false, "DRAFT", null));
        log.info("dev data created: admins student-council / ai-council / cs-council, password '{}'", PASSWORD);
    }

    private CurrentUser createAdmin(String loginId, String name, String orgName) {
        Organization org = new Organization(orgName, OrganizationType.STUDENT_GOVERNMENT);
        org.markVerified();
        org = organizations.save(org);
        Admin admin = admins.save(new Admin(loginId, encoder.encode(PASSWORD), name, org));
        return new CurrentUser(admin.getId(), "ADMIN", org.getId());
    }

    private static NoticeRequest req(String title, String body, List<Long> categoryIds, Instant eventStart,
                                     Instant eventEnd, Instant deadline, String location, boolean urgent,
                                     String mode, Instant publishAt) {
        return new NoticeRequest(title, body, categoryIds, List.of(), eventStart, eventEnd, deadline, location,
                "", urgent, mode, publishAt);
    }
}
