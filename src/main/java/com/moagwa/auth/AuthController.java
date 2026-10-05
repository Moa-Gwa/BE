package com.moagwa.auth;

import com.moagwa.admin.Admin;
import com.moagwa.admin.AdminRepository;
import com.moagwa.common.exception.ApiException;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class AuthController {

    private final AdminRepository admins;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    @Value("${moagwa.kakao.client-id:}")
    private String kakaoClientId;

    @Value("${moagwa.kakao.client-secret:}")
    private String kakaoClientSecret;

    @PostMapping("/admin/auth/login")
    @Transactional(readOnly = true)
    AdminSession adminLogin(@Valid @RequestBody AdminLogin body) {
        Admin admin = admins.findByLoginId(body.loginId())
                .filter(a -> encoder.matches(body.password(), a.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."));
        var org = admin.getOrganization();
        return new AdminSession(jwt.issueAdmin(admin.getId(), org.getId()), admin.getName(),
                new OrgInfo(org.getId(), org.getName(), org.getType().name()));
    }

    /** Exchanges a Kakao authorization code for our own session. Needs moagwa.kakao.client-id to be configured. */
    @PostMapping("/auth/kakao")
    @Transactional
    StudentSession kakaoLogin(@Valid @RequestBody KakaoLogin body) {
        if (kakaoClientId.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "카카오 로그인이 설정되지 않았습니다.");
        }
        RestClient http = RestClient.create();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", kakaoClientId);
        form.add("redirect_uri", body.redirectUri());
        form.add("code", body.code());
        if (!kakaoClientSecret.isBlank()) {
            form.add("client_secret", kakaoClientSecret);
        }
        Map<?, ?> token;
        Map<?, ?> profile;
        try {
            token = http.post().uri("https://kauth.kakao.com/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
            profile = http.get().uri("https://kapi.kakao.com/v2/user/me")
                    .header("Authorization", "Bearer " + token.get("access_token")).retrieve().body(Map.class);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "카카오 인증에 실패했습니다.");
        }
        String kakaoId = String.valueOf(profile.get("id"));
        String nickname = profile.get("properties") instanceof Map<?, ?> props && props.get("nickname") != null
                ? String.valueOf(props.get("nickname")) : "학생";
        return session(findOrCreate("KAKAO", kakaoId, nickname));
    }

    /** Local-development shortcut so the app is usable without a Kakao app registration. */
    @RestController
    @RequestMapping("/api/auth")
    @Profile("local")
    @RequiredArgsConstructor
    static class DevAuthController {

        private final UserRepository users;
        private final JwtService jwt;

        @PostMapping("/dev-student")
        @Transactional
        StudentSession devStudent(@Valid @RequestBody DevLogin body) {
            User user = users.findByAuthProviderAndProviderUserId("DEV", body.nickname())
                    .orElseGet(() -> users.save(new User("DEV", body.nickname(), body.nickname())));
            return new StudentSession(jwt.issueStudent(user.getId()), user.getNickname(),
                    user.getPrimaryDepartmentId() != null);
        }
    }

    private User findOrCreate(String provider, String providerUserId, String nickname) {
        return users.findByAuthProviderAndProviderUserId(provider, providerUserId)
                .orElseGet(() -> users.save(new User(provider, providerUserId, nickname)));
    }

    private StudentSession session(User user) {
        return new StudentSession(jwt.issueStudent(user.getId()), user.getNickname(),
                user.getPrimaryDepartmentId() != null);
    }

    record AdminLogin(@NotBlank String loginId, @NotBlank String password) {
    }

    record KakaoLogin(@NotBlank String code, @NotBlank String redirectUri) {
    }

    record DevLogin(@NotBlank String nickname) {
    }

    record OrgInfo(long id, String name, String type) {
    }

    record AdminSession(String token, String name, OrgInfo organization) {
    }

    record StudentSession(String token, String nickname, boolean onboarded) {
    }
}
