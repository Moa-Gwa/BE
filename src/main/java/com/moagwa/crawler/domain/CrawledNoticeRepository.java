package com.moagwa.crawler.domain;

import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CrawledNoticeRepository extends JpaRepository<CrawledNotice, Long> {

    @Query("select n.externalId from CrawledNotice n where n.source.id = :sourceId and n.externalId in :externalIds")
    Set<String> findExistingExternalIds(@Param("sourceId") Long sourceId,
                                        @Param("externalIds") List<String> externalIds);

    @Query("select n from CrawledNotice n where n.source.id = :sourceId and n.contentFetched = false order by n.id desc")
    List<CrawledNotice> findUnfetched(@Param("sourceId") Long sourceId, Pageable pageable);

    /** Back-fills the category of already stored notices that were saved before categories were parsed. */
    @Modifying
    @Query("update CrawledNotice n set n.category = :category where n.source.id = :sourceId "
            + "and n.externalId in :externalIds and n.category is null")
    int fillMissingCategory(@Param("sourceId") Long sourceId, @Param("externalIds") List<String> externalIds,
                            @Param("category") String category);

}
