package com.moagwa.auth;

import com.moagwa.user.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** Issues and verifies the HS256 access tokens used by both students and admins. */
@Service
public class JwtService {

    /** 액세스 토큰과 세션 쿠키의 유효 기간. */
    public static final Duration TTL = Duration.ofMinutes(15);

    private final SecretKey key;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtService(@Value("${moagwa.jwt.secret}") String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("moagwa.jwt.secret must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    /** 로그인, 토큰 갱신용. 호출자가 동의 이력에서 확인한 상태만 토큰에 넣는다. */
    public String issueStudent(User user, boolean consented) {
        return issue(user.getId(), "STUDENT", null, user.isVerified(), false, consented);
    }

    /** 약관에 동의한 학생 토큰. 동의한 학생만 부르는 API(재학생 인증 등)와 테스트에서 씀 */
    public String issueStudent(long userId) {
        return issueStudent(userId, false);
    }

    /** verified = 학교 메일로 재학생 인증을 마친 학생. 생활거래 API가 이 값을 확인한다. */
    public String issueStudent(long userId, boolean verified) {
        return issue(userId, "STUDENT", null, verified, false, true);
    }

    public String issueAdmin(long adminId, long organizationId) {
        return issueAdmin(adminId, organizationId, false);
    }

    /** operator = 서비스 운영자. 운영자 전용 API가 이 값을 확인한다. */
    public String issueAdmin(long adminId, long organizationId, boolean operator) {
        return issue(adminId, "ADMIN", organizationId, false, operator, false);
    }

    private String issue(long id, String role, Long organizationId, boolean verified, boolean operator,
            boolean consented) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(String.valueOf(id))
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .claim("role", role);
        if (organizationId != null) {
            claims.claim("org", organizationId);
        }
        if (verified) {
            claims.claim("verified", true);
        }
        if (operator) {
            claims.claim("operator", true);
        }
        if (consented) {
            claims.claim("consented", true);
            claims.claim("consentVersion", User.TERMS_VERSION);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
