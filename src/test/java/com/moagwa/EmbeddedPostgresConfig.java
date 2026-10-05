package com.moagwa;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Real PostgreSQL (no Docker) shared by all Spring tests, so Flyway migrations and the feed view are exercised. */
@Configuration
class EmbeddedPostgresConfig {

    private static EmbeddedPostgres postgres;

    @Bean
    @Primary
    DataSource dataSource() throws IOException {
        synchronized (EmbeddedPostgresConfig.class) {
            if (postgres == null) {
                postgres = EmbeddedPostgres.builder().start();
            }
            return postgres.getPostgresDatabase();
        }
    }
}
