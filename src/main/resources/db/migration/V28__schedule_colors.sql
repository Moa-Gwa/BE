CREATE TABLE schedule_colors (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source VARCHAR(10) NOT NULL CHECK (source IN ('MANUAL','CRAWLED','PERSONAL','ACADEMIC')),
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('EVENT','DEADLINE','PERSONAL','ACADEMIC')),
    target_id BIGINT NOT NULL CHECK (target_id > 0),
    color VARCHAR(7) NOT NULL CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    PRIMARY KEY (user_id, source, entry_type, target_id)
);
