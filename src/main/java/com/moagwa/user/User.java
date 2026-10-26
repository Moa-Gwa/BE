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

    public User(String authProvider, String providerUserId, String nickname) {
        this.authProvider = authProvider;
        this.providerUserId = providerUserId;
        this.nickname = nickname;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public void updateProfile(String nickname, Long primaryDepartmentId, Integer admissionYear) {
        if (nickname != null && !nickname.isBlank()) {
            this.nickname = nickname;
        }
        this.primaryDepartmentId = primaryDepartmentId;
        this.admissionYear = admissionYear;
    }
}
