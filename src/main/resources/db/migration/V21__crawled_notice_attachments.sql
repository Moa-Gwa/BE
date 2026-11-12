ALTER TABLE crawl_sources ADD COLUMN attachment_selector VARCHAR(500) NOT NULL DEFAULT '.b-file-box a[href]';
ALTER TABLE crawled_notices ADD COLUMN media_fetched BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE crawled_notices ADD COLUMN media_attempted_at TIMESTAMPTZ;
CREATE TABLE crawled_notice_attachments (
    notice_id BIGINT NOT NULL REFERENCES crawled_notices(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL,
    file_name TEXT NOT NULL,
    url TEXT NOT NULL,
    PRIMARY KEY (notice_id, sort_order)
);
CREATE INDEX idx_crawled_notices_missing_media ON crawled_notices(source_id, id) WHERE media_fetched = FALSE;
