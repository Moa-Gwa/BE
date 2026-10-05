package com.moagwa.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "organizations")
@Getter
@NoArgsConstructor
public class Organization {

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
