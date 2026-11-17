package com.moagwa.auth;

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.ArrayList;
import java.util.List;
import com.moagwa.user.User;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtService jwt) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        // role 클레임은 ROLE_STUDENT / ROLE_ADMIN, verified 클레임(재학생 인증 완료)은 ROLE_VERIFIED,
        // operator 클레임(서비스 운영자)은 ROLE_OPERATOR가 된다.
        // 약관에 동의하지 않은 학생은 ROLE_UNCONSENTED만 받아 동의, 탈퇴 API 외에는 403이 된다.
        converter.setJwtGrantedAuthoritiesConverter(jwtToken -> {
            List<GrantedAuthority> granted = new ArrayList<>();
            String role = jwtToken.getClaimAsString("role");
            if ("STUDENT".equals(role) && (!Boolean.TRUE.equals(jwtToken.getClaim("consented"))
                    || !User.TERMS_VERSION.equals(jwtToken.getClaimAsString("consentVersion")))) {
                return List.of(new SimpleGrantedAuthority("ROLE_UNCONSENTED"));
            }
            if (role != null) {
                granted.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            if (Boolean.TRUE.equals(jwtToken.getClaim("verified"))) {
                granted.add(new SimpleGrantedAuthority("ROLE_VERIFIED"));
            }
            if (Boolean.TRUE.equals(jwtToken.getClaim("operator"))) {
                granted.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
            }
            return granted;
        });

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/api/auth/**", "/api/admin/auth/**", "/api/stream", "/uploads/**", "/instagram/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/feed/**", "/api/notices/**", "/api/crawled-notices/**",
                                "/api/organizations/**", "/api/categories", "/api/schedule").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/notices/*/view", "/api/crawled-notices/*/view")
                        .hasRole("STUDENT")
                        .requestMatchers(HttpMethod.POST, "/api/crawled-notices/*/date-corrections").hasRole("STUDENT")
                        .requestMatchers("/api/admin/crawler/categories/**").hasRole("OPERATOR")
                        .requestMatchers("/api/admin/applications/**").hasRole("OPERATOR")
                        .requestMatchers("/api/admin/date-corrections/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/admin-applications").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/me/consent").hasAnyRole("STUDENT", "UNCONSENTED")
                        .requestMatchers(HttpMethod.DELETE, "/api/me").hasAnyRole("STUDENT", "UNCONSENTED")
                        .requestMatchers("/api/market/**", "/api/me/market/**").hasRole("VERIFIED")
                        .requestMatchers("/ws", "/api/chats/**", "/api/me/chats").hasRole("VERIFIED")
                        .requestMatchers("/api/me/**").hasRole("STUDENT")
                        .requestMatchers("/error", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(new CookieCsrfFilter(), BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(o -> o.bearerTokenResolver(new AuthTokenResolver(jwt.decoder()))
                        .jwt(j -> j.decoder(jwt.decoder()).jwtAuthenticationConverter(converter)));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
