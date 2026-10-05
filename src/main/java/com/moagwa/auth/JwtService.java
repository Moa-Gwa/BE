package com.moagwa.auth;

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

    private static final Duration TTL = Duration.ofHours(12);

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

    public String issueStudent(long userId) {
        return issue(userId, "STUDENT", null);
    }

    public String issueAdmin(long adminId, long organizationId) {
        return issue(adminId, "ADMIN", organizationId);
    }

    private String issue(long id, String role, Long organizationId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(String.valueOf(id))
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .claim("role", role);
        if (organizationId != null) {
            claims.claim("org", organizationId);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
