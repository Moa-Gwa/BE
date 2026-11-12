package com.moagwa.crawler.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CrawledNoticeRepository extends JpaRepository<CrawledNotice, Long> {

    @Query("select n from CrawledNotice n where n.source.id = :sourceId and n.externalId = :externalId")
    java.util.Optional<CrawledNotice> findBySourceAndExternalId(@Param("sourceId") Long sourceId,
                                                                 @Param("externalId") String externalId);

    @Query("select n.externalId from CrawledNotice n where n.source.id = :sourceId and n.externalId in :externalIds")
    Set<String> findExistingExternalIds(@Param("sourceId") Long sourceId,
                                        @Param("externalIds") List<String> externalIds);

    @Query("select n from CrawledNotice n where n.source.id = :sourceId and n.externalId in :externalIds")
    List<CrawledNotice> findBySourceIdAndExternalIdIn(@Param("sourceId") Long sourceId,
                                                       @Param("externalIds") List<String> externalIds);

    @Query("select n from CrawledNotice n where n.source.id = :sourceId and n.contentFetched = false order by n.id desc")
    List<CrawledNotice> findUnfetched(@Param("sourceId") Long sourceId, Pageable pageable);

    /** 미디어 미확인 공지 중 처음 시도하거나 마지막 시도가 retryBefore 이전인 것 */
    @Query("select n from CrawledNotice n where n.source.id = :sourceId and n.contentFetched = true "
            + "and n.mediaFetched = false "
            + "and (n.mediaAttemptedAt is null or n.mediaAttemptedAt < :retryBefore) "
            + "order by n.mediaAttemptedAt nulls first, n.id")
    List<CrawledNotice> findMissingMedia(@Param("sourceId") Long sourceId, @Param("retryBefore") Instant retryBefore,
                                         Pageable pageable);

    /** Back-fills the category of already stored notices that were saved before categories were parsed. */
    @Modifying
    @Query("update CrawledNotice n set n.category = :category where n.source.id = :sourceId "
            + "and n.externalId in :externalIds and n.category is null")
    int fillMissingCategory(@Param("sourceId") Long sourceId, @Param("externalIds") List<String> externalIds,
                            @Param("category") String category);

}
