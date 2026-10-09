-- 크롤링 공지(인스타그램 카드뉴스 등)의 본문 이미지. 첫 장이 피드 썸네일이 된다.
CREATE TABLE crawled_notice_images (
    notice_id   BIGINT       NOT NULL REFERENCES crawled_notices (id) ON DELETE CASCADE,
    sort_order  INT          NOT NULL,
    url         VARCHAR(500) NOT NULL,
    PRIMARY KEY (notice_id, sort_order)
);
