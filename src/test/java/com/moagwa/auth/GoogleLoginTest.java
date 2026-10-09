package com.moagwa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.moagwa.user.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "moagwa.google.allowed-email-domain=school.ac.kr")
@AutoConfigureMockMvc
class GoogleLoginTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @MockBean FirebaseAuth firebaseAuth;

    private FirebaseToken token(String uid, String email, boolean verified, String provider, String name) {
        FirebaseToken t = mock(FirebaseToken.class);
        when(t.getUid()).thenReturn(uid);
        when(t.getEmail()).thenReturn(email);
        when(t.isEmailVerified()).thenReturn(verified);
        when(t.getName()).thenReturn(name);
        when(t.getClaims()).thenReturn(Map.of("firebase", Map.of("sign_in_provider", provider)));
        return t;
    }

    private org.springframework.test.web.servlet.ResultActions login(String idToken) throws Exception {
        return mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"" + idToken + "\"}"));
    }

    @Test
    void validGoogleTokenCreatesStudentOnceAndReusesIt() throws Exception {
        String uid = "g-" + UUID.randomUUID();
        FirebaseToken t_ok = token(uid, "kim@school.ac.kr", true, "google.com", "김모아");
        when(firebaseAuth.verifyIdToken("ok")).thenReturn(t_ok);

        login("ok").andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.nickname").value("김모아")).andExpect(jsonPath("$.onboarded").value(false));
        login("ok").andExpect(status().isOk());

        assertThat(users.findByAuthProviderAndProviderUserId("GOOGLE", uid)).isPresent();
        assertThat(users.findAll().stream().filter(u -> uid.equals(u.getProviderUserId())).count()).isEqualTo(1);
    }

    @Test
    void emailLocalPartIsUsedWhenGoogleNameIsMissing() throws Exception {
        FirebaseToken t_noname = token("g-" + UUID.randomUUID(), "park@school.ac.kr", true, "google.com", null);
        when(firebaseAuth.verifyIdToken("noname")).thenReturn(t_noname);
        login("noname").andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("park"));
    }

    @Test
    void rejectsInvalidTokenNonGoogleProviderAndUnverifiedEmail() throws Exception {
        when(firebaseAuth.verifyIdToken("bad")).thenThrow(mock(FirebaseAuthException.class));
        login("bad").andExpect(status().isUnauthorized());

        FirebaseToken t_pw = token("g-1", "a@school.ac.kr", true, "password", "x");
        when(firebaseAuth.verifyIdToken("pw")).thenReturn(t_pw);
        login("pw").andExpect(status().isUnauthorized());

        FirebaseToken t_unverified = token("g-2", "a@school.ac.kr", false, "google.com", "x");
        when(firebaseAuth.verifyIdToken("unverified")).thenReturn(t_unverified);
        login("unverified").andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsOtherEmailDomainsWhenARestrictionIsConfigured() throws Exception {
        FirebaseToken t_gmail = token("g-3", "me@gmail.com", true, "google.com", "x");
        when(firebaseAuth.verifyIdToken("gmail")).thenReturn(t_gmail);
        login("gmail").andExpect(status().isForbidden());
        assertThat(users.findByAuthProviderAndProviderUserId("GOOGLE", "g-3")).isEmpty();
    }

    @Test
    void blankTokenIsABadRequest() throws Exception {
        login("").andExpect(status().isBadRequest());
    }
}
