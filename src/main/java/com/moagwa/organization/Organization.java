package com.moagwa.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "organizations")
@Getter
@NoArgsConstructor
public class Organization {

    /** 서비스 운영자 계정이 소속되는 필수 단체 이름 */
    public static final String OPERATOR_NAME = "운영자";

    /** 학과명과 학과 학생회 이름이 "학과명 학생회" 규칙과 다른 경우 */
    private static final Map<String, String> COUNCIL_NAMES = Map.of(
            "영어영문학과", "영어영문학부 학생회",
            "일본어일본문화학과", "일어일본문화학과 학생회",
            "중국어중국학과", "중국언어문화학과 학생회",
            "프랑스어프랑스문화학과", "프랑스어문화학과 학생회",
            "경영학부", "경영학과 학생회");

    /** 학과 학생회 이름. 학생회는 인스타 수집, 관리자 신청 승인 때 생기므로 이름으로 찾음 */
    public static String councilNameOf(String departmentName) {
        return COUNCIL_NAMES.getOrDefault(departmentName, departmentName + " 학생회");
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrganizationType type;

    private String college;

    private String department;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "profile_image")
    private String profileImage;

    @Column(name = "external_url")
    private String externalUrl;

    @Column(name = "is_verified", nullable = false)
    private boolean verified;

    /** 운영자처럼 구독 해제할 수 없는 단체. 관심 단체 설정과 무관하게 피드와 알림 대상에 항상 포함됨 */
    @Column(name = "is_required", nullable = false)
    private boolean required;

    public Organization(String name, OrganizationType type) {
        this.name = name;
        this.type = type;
    }

    public void updateInfo(String description, String profileImage, String externalUrl) {
        this.description = description;
        this.profileImage = profileImage;
        this.externalUrl = externalUrl;
    }

    public void markVerified() {
        this.verified = true;
    }
}
