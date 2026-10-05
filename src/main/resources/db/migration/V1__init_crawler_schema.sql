CREATE TABLE organizations (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(100) NOT NULL UNIQUE,
    type                    VARCHAR(30)  NOT NULL,
    student_government_type VARCHAR(30),
    college                 VARCHAR(100),
    department              VARCHAR(100),
    description             TEXT,
    profile_image           VARCHAR(500),
    external_url            VARCHAR(500),
    is_verified             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE crawl_sources (
    id                    BIGSERIAL PRIMARY KEY,
    code                  VARCHAR(100)  NOT NULL UNIQUE,
    organization_id       BIGINT        NOT NULL REFERENCES organizations (id),
    list_url              VARCHAR(500)  NOT NULL,
    row_selector          VARCHAR(200)  NOT NULL DEFAULT 'table tbody tr',
    link_selector         VARCHAR(200)  NOT NULL DEFAULT 'a.b-title',
    date_selector         VARCHAR(200)  NOT NULL DEFAULT '.b-date',
    body_selector         VARCHAR(200)  NOT NULL DEFAULT '.b-con-box',
    enabled               BOOLEAN       NOT NULL DEFAULT TRUE,
    last_crawled_at       TIMESTAMPTZ,
    last_status           VARCHAR(20),
    last_error            VARCHAR(1000),
    consecutive_failures  INT           NOT NULL DEFAULT 0
);

CREATE TABLE crawled_notices (
    id               BIGSERIAL PRIMARY KEY,
    source_id        BIGINT        NOT NULL REFERENCES crawl_sources (id),
    external_id      VARCHAR(100)  NOT NULL,
    title            VARCHAR(500)  NOT NULL,
    url              VARCHAR(1000) NOT NULL,
    content_html     TEXT,
    content_text     TEXT,
    content_fetched  BOOLEAN       NOT NULL DEFAULT FALSE,
    pinned           BOOLEAN       NOT NULL DEFAULT FALSE,
    published_at     TIMESTAMPTZ   NOT NULL,
    crawled_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    view_count       INT           NOT NULL DEFAULT 0,
    category         VARCHAR(30),
    CONSTRAINT uq_crawled_notices_source_external UNIQUE (source_id, external_id)
);

CREATE INDEX idx_crawled_notices_published_at ON crawled_notices (published_at DESC);
CREATE INDEX idx_crawled_notices_unfetched ON crawled_notices (source_id) WHERE content_fetched = FALSE;
