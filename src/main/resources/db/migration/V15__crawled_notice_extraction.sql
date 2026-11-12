-- 크롤링 공지 본문에서 추출한 마감일·행사일·키워드
ALTER TABLE crawled_notices
    ADD COLUMN deadline_at          TIMESTAMPTZ,
    ADD COLUMN event_start_at       TIMESTAMPTZ,
    ADD COLUMN event_end_at         TIMESTAMPTZ,
    ADD COLUMN keywords             TEXT[]      NOT NULL DEFAULT '{}',
    -- 추출 근거가 된 원문 문장. 상세 화면에서 원문 확인용으로 표시
    ADD COLUMN date_evidence        TEXT,
    ADD COLUMN extracted_at         TIMESTAMPTZ,
    ADD COLUMN extraction_attempts  INT         NOT NULL DEFAULT 0;

-- 추출 대기 공지 조회용
CREATE INDEX idx_crawled_notices_extraction_pending ON crawled_notices (published_at DESC)
    WHERE extracted_at IS NULL AND content_fetched;

DROP VIEW v_feed_items;
CREATE VIEW v_feed_items AS
SELECT 'MANUAL'::varchar                       AS source,
       n.id                                    AS id,
       n.organization_id                       AS organization_id,
       o.name                                  AS organization_name,
       o.type                                  AS organization_type,
       n.title                                 AS title,
       left(n.description, 120)                AS summary,
       (SELECT c.code FROM notice_categories nc JOIN categories c ON c.id = nc.category_id
         WHERE nc.notice_id = n.id ORDER BY c.sort_order LIMIT 1) AS category,
       n.is_urgent                             AS is_urgent,
       false                                   AS pinned,
       n.publish_at                            AS published_at,
       n.event_start_at                        AS event_start_at,
       n.event_end_at                          AS event_end_at,
       n.deadline_at                           AS deadline_at,
       n.location                              AS location,
       n.view_count                            AS view_count,
       (SELECT i.url FROM notice_images i WHERE i.notice_id = n.id ORDER BY i.sort_order LIMIT 1) AS thumbnail,
       CASE
         WHEN n.status = 'CLOSED' OR n.deadline_at < now() THEN 'CLOSED'
         WHEN n.event_end_at < now() THEN 'ENDED'
         WHEN n.deadline_at IS NOT NULL AND n.deadline_at - now() <= interval '24 hours' THEN 'DEADLINE_SOON'
         WHEN n.event_start_at <= now() THEN 'ONGOING'
         WHEN n.event_start_at > now() THEN 'UPCOMING'
         ELSE NULL
       END                                     AS display_status,
       NULL::text                              AS keywords
  FROM notices n
  JOIN organizations o ON o.id = n.organization_id
 WHERE n.deleted_at IS NULL
   AND n.status IN ('PUBLISHED', 'CLOSED')
UNION ALL
SELECT 'CRAWLED'::varchar,
       c.id,
       s.organization_id,
       o.name,
       o.type,
       c.title,
       left(c.content_text, 120),
       c.category,
       false,
       c.pinned,
       c.published_at,
       c.event_start_at,
       c.event_end_at,
       c.deadline_at,
       NULL::varchar,
       c.view_count,
       c.thumbnail_url,
       CASE
         WHEN c.deadline_at < now() THEN 'CLOSED'
         WHEN c.event_end_at < now() THEN 'ENDED'
         WHEN c.deadline_at IS NOT NULL AND c.deadline_at - now() <= interval '24 hours' THEN 'DEADLINE_SOON'
         WHEN c.event_start_at <= now() THEN 'ONGOING'
         WHEN c.event_start_at > now() THEN 'UPCOMING'
         ELSE NULL
       END,
       array_to_string(c.keywords, ' ')
  FROM crawled_notices c
  JOIN crawl_sources s ON s.id = c.source_id
  JOIN organizations o ON o.id = s.organization_id;
