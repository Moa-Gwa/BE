-- Blog-style body: ordered text/image blocks stored as JSON. description and notice_images stay populated
-- (derived from the blocks) for search, summaries, notifications and thumbnails.
ALTER TABLE notices ADD COLUMN content_blocks TEXT;
