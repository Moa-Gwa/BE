package com.moagwa.crawler.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CrawlSourceRepository extends JpaRepository<CrawlSource, Long> {

    java.util.Optional<CrawlSource> findByCode(String code);

    @Query("select s from CrawlSource s join fetch s.organization where s.enabled = true order by s.id")
    List<CrawlSource> findAllEnabled();
}
