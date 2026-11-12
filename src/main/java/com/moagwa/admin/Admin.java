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

    @Column(nullable = false)
    private String role = "ORG_ADMIN";

    public Admin(String loginId, String passwordHash, String name, Organization organization) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.organization = organization;
    }

    /** 서비스 운영자. 단체 소속과 별개로 크롤링 공지 날짜 교정 등 서비스 전체 관리 권한 보유 */
    public boolean isOperator() {
        return "OPERATOR".equals(role);
    }

    void grantOperator() {
        this.role = "OPERATOR";
    }
}
