package com.moagwa.admin;

import com.moagwa.organization.Organization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One admin account belongs to exactly one organization. */
@Entity
@Table(name = "admins")
@Getter
@NoArgsConstructor
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_id", nullable = false, unique = true)
    private String loginId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    /** false면 로그인 불가. 신청으로 발급된 계정은 비밀번호 설정 후 true가 됨 */
    @Column(nullable = false)
    private boolean activated = true;

    @Column(nullable = false)
    private String role = "ORG_ADMIN";

    public Admin(String loginId, String passwordHash, String name, Organization organization) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.organization = organization;
    }

    /** 승인됐지만 담당자가 아직 비밀번호를 설정하지 않은 계정. 설정 전에는 로그인할 수 없음 */
    public static Admin awaitingSetup(String loginId, String unusablePasswordHash, String name,
                                      Organization organization) {
        Admin admin = new Admin(loginId, unusablePasswordHash, name, organization);
        admin.activated = false;
        return admin;
    }

    /** 담당자가 정한 비밀번호로 계정을 활성화함 */
    public void activate(String passwordHash) {
        this.passwordHash = passwordHash;
        this.activated = true;
    }

    /** 서비스 운영자. 단체 소속과 별개로 크롤링 공지 날짜 교정 등 서비스 전체 관리 권한 보유 */
    public boolean isOperator() {
        return "OPERATOR".equals(role);
    }

    /** 설정으로 운영자가 된 계정을 운영자 단체로 옮김 */
    public void moveTo(Organization organization) {
        this.organization = organization;
    }

    void grantOperator() {
        this.role = "OPERATOR";
    }
}
