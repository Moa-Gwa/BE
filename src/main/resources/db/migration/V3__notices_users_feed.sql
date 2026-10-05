CREATE TABLE categories (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(30) NOT NULL UNIQUE,
    name        VARCHAR(30) NOT NULL,
    sort_order  INT         NOT NULL
);

INSERT INTO categories (code, name, sort_order) VALUES
    ('ACADEMIC',    '학사',     1),
    ('SCHOLARSHIP', '장학',     2),
    ('WELFARE',     '복지',     3),
    ('EVENT',       '행사',     4),
    ('SNACK',       '간식',     5),
    ('PARTNERSHIP', '제휴',     6),
    ('STUDENT_FEE', '학생회비', 7),
    ('RECRUIT',     '모집',     8),
    ('ETC',         '기타',     9);

CREATE TABLE admins (
    id               BIGSERIAL PRIMARY KEY,
    login_id         VARCHAR(50)  NOT NULL UNIQUE,
    password_hash    VARCHAR(100) NOT NULL,
    name             VARCHAR(50)  NOT NULL,
    organization_id  BIGINT       NOT NULL REFERENCES organizations (id),
    role             VARCHAR(30)  NOT NULL DEFAULT 'ORG_ADMIN',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE notices (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organizations (id),
    author_id        BIGINT       NOT NULL REFERENCES admins (id),
    title            VARCHAR(40)  NOT NULL,
    description      TEXT         NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    publish_at       TIMESTAMPTZ,
    event_start_at   TIMESTAMPTZ,
    event_end_at     TIMESTAMPTZ,
    deadline_at      TIMESTAMPTZ,
    location         VARCHAR(200),
    external_link    VARCHAR(500),
    is_urgent        BOOLEAN      NOT NULL DEFAULT FALSE,
    view_count       INT          NOT NULL DEFAULT 0,
    bookmark_count   INT          NOT NULL DEFAULT 0,
    share_count      INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ
);
CREATE INDEX idx_notices_org_status ON notices (organization_id, status);
CREATE INDEX idx_notices_publish_at ON notices (publish_at DESC);

CREATE TABLE notice_categories (
    notice_id    BIGINT NOT NULL REFERENCES notices (id) ON DELETE CASCADE,
    category_id  BIGINT NOT NULL REFERENCES categories (id),
    PRIMARY KEY (notice_id, category_id)
);

CREATE TABLE notice_images (
    id          BIGSERIAL PRIMARY KEY,
    notice_id   BIGINT       NOT NULL REFERENCES notices (id) ON DELETE CASCADE,
    url         VARCHAR(500) NOT NULL,
    sort_order  INT          NOT NULL
);

CREATE TABLE users (
    id                    BIGSERIAL PRIMARY KEY,
    auth_provider         VARCHAR(20)  NOT NULL,
    provider_user_id      VARCHAR(100) NOT NULL,
    nickname              VARCHAR(50)  NOT NULL,
    primary_department_id BIGINT REFERENCES organizations (id),
    admission_year        INT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_provider UNIQUE (auth_provider, provider_user_id)
);

CREATE TABLE user_interests (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       VARCHAR(20) NOT NULL,
    target_id  BIGINT      NOT NULL,
    PRIMARY KEY (user_id, kind, target_id)
);

CREATE TABLE bookmarks (
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source      VARCHAR(10) NOT NULL,
    notice_id   BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, source, notice_id)
);

CREATE TABLE notification_settings (
    user_id          BIGINT  PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    new_notice       BOOLEAN NOT NULL DEFAULT TRUE,
    event_start      BOOLEAN NOT NULL DEFAULT TRUE,
    deadline_soon    BOOLEAN NOT NULL DEFAULT TRUE,
    urgent           BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(30)  NOT NULL,
    source      VARCHAR(10)  NOT NULL,
    notice_id   BIGINT       NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        VARCHAR(500),
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);

-- Unified student feed over directly-authored notices and crawled notices.
-- display_status is computed at query time from the clock (never stored).
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
       NULL::varchar,
       NULL::text
  FROM crawled_notices c
  JOIN crawl_sources s ON s.id = c.source_id
  JOIN organizations o ON o.id = s.organization_id;

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_notices_title_trgm ON notices USING gin (title gin_trgm_ops);
CREATE INDEX idx_crawled_notices_title_trgm ON crawled_notices USING gin (title gin_trgm_ops);
