package com.moagwa.notice;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    @Query("select n from Notice n where n.id = :id and n.deletedAt is null")
    Optional<Notice> findActive(@Param("id") Long id);

    @Query("select n from Notice n where n.id = :id and n.organization.id = :orgId and n.deletedAt is null")
    Optional<Notice> findActiveByOrganization(@Param("id") Long id, @Param("orgId") Long organizationId);

    @Query("select n from Notice n where n.organization.id = :orgId and n.deletedAt is null")
    List<Notice> findAllActiveByOrganization(@Param("orgId") Long organizationId);

    @Query("select n from Notice n where n.status = com.moagwa.notice.NoticeStatus.SCHEDULED "
            + "and n.publishAt <= :now and n.deletedAt is null")
    List<Notice> findDueScheduled(@Param("now") Instant now);

    @Modifying
    @Query("update Notice n set n.viewCount = n.viewCount + 1 where n.id = :id")
    int incrementViews(@Param("id") Long id);
}
