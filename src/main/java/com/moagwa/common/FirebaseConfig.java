package com.moagwa.common;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Firebase Admin clients (FCM push and ID-token verification for Google sign-in). Only created when a
 * service-account key path is configured; otherwise push delivery is skipped and Google sign-in answers 503.
 */
@Configuration
@ConditionalOnExpression("!'${moagwa.firebase.credentials-path:}'.isEmpty()")
class FirebaseConfig {

    @Bean
    FirebaseApp firebaseApp(@Value("${moagwa.firebase.credentials-path}") String credentialsPath) throws IOException {
        return FirebaseApp.getApps().stream().findFirst().orElseGet(() -> {
            try (InputStream key = new FileInputStream(credentialsPath)) {
                return FirebaseApp.initializeApp(FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(key)).build());
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read Firebase credentials: " + credentialsPath, e);
            }
        });
    }

    @Bean
    FirebaseMessaging firebaseMessaging(FirebaseApp app) {
        return FirebaseMessaging.getInstance(app);
    }

    @Bean
    FirebaseAuth firebaseAuth(FirebaseApp app) {
        return FirebaseAuth.getInstance(app);
    }
}
