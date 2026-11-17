package com.moagwa.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {

    /** 현재 이용약관, 개인정보 수집/이용 동의 문서 버전. 문서를 바꾸면 올려서 모든 학생에게 다시 동의를 받음 */
    public static final String TERMS_VERSION = "2026-11-17";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auth_provider", nullable = false)
    private String authProvider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(nullable = false)
    private String nickname;

    @Column(name = "primary_department_id")
    private Long primaryDepartmentId;

    @Column(name = "admission_year")
    private Integer admissionYear;

    @Column(name = "verified_at")
    private java.time.Instant verifiedAt;

    @Column(name = "terms_version")
    private String termsVersion;

    @Column(name = "terms_agreed_at")
    private java.time.Instant termsAgreedAt;

    public User(String authProvider, String providerUserId, String nickname) {
        this.authProvider = authProvider;
        this.providerUserId = providerUserId;
        this.nickname = nickname;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean hasConsented() {
        return TERMS_VERSION.equals(termsVersion);
    }

    public void consent() {
        this.termsVersion = TERMS_VERSION;
        this.termsAgreedAt = java.time.Instant.now();
    }

    public void updateProfile(String nickname, Long primaryDepartmentId, Integer admissionYear) {
        if (nickname != null && !nickname.isBlank()) {
            this.nickname = nickname;
        }
        this.primaryDepartmentId = primaryDepartmentId;
        this.admissionYear = admissionYear;
    }
}
