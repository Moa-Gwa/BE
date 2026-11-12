-- 크롤링 공지의 썸네일(인스타그램 대표 이미지 등)을 피드에 노출한다.
ALTER TABLE crawled_notices ADD COLUMN thumbnail_url VARCHAR(500);

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
       END                                     AS display_status
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
       NULL::timestamptz,
       NULL::timestamptz,
       NULL::timestamptz,
       NULL::varchar,
       c.view_count,
       c.thumbnail_url,
       NULL::text
  FROM crawled_notices c
  JOIN crawl_sources s ON s.id = c.source_id
  JOIN organizations o ON o.id = s.organization_id;
