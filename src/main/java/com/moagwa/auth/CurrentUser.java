package com.moagwa.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** Identity extracted from the verified JWT. Controllers never trust ids or organization ids from request bodies. */
public record CurrentUser(long id, String role, Long organizationId) {

    public static CurrentUser from(Authentication auth) {
        Jwt jwt = (Jwt) auth.getPrincipal();
        Long org = jwt.hasClaim("org") ? ((Number) jwt.getClaim("org")).longValue() : null;
        return new CurrentUser(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("role"), org);
    }

    /** Returns the signed-in student's id or null for anonymous requests. */
    public static Long studentIdOrNull(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt)) {
            return null;
        }
        CurrentUser user = from(auth);
        return "STUDENT".equals(user.role()) ? user.id() : null;
    }
}
