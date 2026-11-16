import test from 'node:test';
import assert from 'node:assert/strict';
import { extractCaption, extractPublishedAt, extractImageUrls, normalizePostUrl, shortcodeFromUrl } from '../src/instagram.js';

test('uses the rendered time, then the post taken_at, and never the description date', () => {
    assert.equal(extractPublishedAt('2026-10-06T12:34:00Z', ''), '2026-10-06T12:34:00.000Z');
    const html = '{"code":"OTHER","taken_at":1700000000} ... {"code":"DdkxCH7EW2o","caption":{},"taken_at":1758514499}';
    assert.equal(extractPublishedAt(null, html, 'DdkxCH7EW2o'), '2025-09-22T04:14:59.000Z');
    assert.equal(extractPublishedAt(null, '{"code":"OTHER","taken_at":1700000000}', 'DdkxCH7EW2o'), null);
    assert.equal(extractPublishedAt(null, '12 likes - cuk_ai on October 6, 2026: "공지".'), null);
    assert.equal(extractPublishedAt(null, ''), null);
});

test('removes metadata counts, author, date and outer quotes without altering the post', () => {
    const caption = '[행사 안내]\n\n본문의 likes, comment와 "인용"은 유지합니다.\n#학생회';
    for (const prefix of ['31 likes, 0 comments - ', '1 like, 1 comment - ', '1.2K likes - ', '']) {
        assert.equal(extractCaption('', `${prefix}cuk_ns on November 9, 2026: "${caption}".`), caption);
    }
});

test('prefers actual caption and does not strip authored likes or comments', () => {
    const caption = '  likes, comments 참여 안내\n본문  ';
    assert.equal(extractCaption(caption, 'unrelated metadata'), caption.trim());
});

test('posts without captions have no platform metadata as their body', () => {
    assert.equal(extractCaption('', 'cuk_chinese on February 26, 2026'), '');
    assert.equal(extractCaption('', '31 likes, 0 comments - cuk_ns on November 9, 2026'), '');
    assert.equal(extractCaption('', 'Instagram • photos and videos'), '');
    assert.equal(extractCaption(''), '');
});

test('normalizes only Instagram post URLs', () => {
    assert.equal(normalizePostUrl('https://instagram.com/p/Ab_C-12/?utm_source=test'),
        'https://www.instagram.com/p/Ab_C-12/');
    assert.equal(normalizePostUrl('https://www.instagram.com/cuk_ai_/'), null);
    assert.equal(shortcodeFromUrl('https://www.instagram.com/reel/Ab_C-12/'), 'Ab_C-12');
});

test('extracts each carousel display URL in page order', () => {
    const html = String.raw`<script>{"edge_sidecar_to_children":{"edges":[
        {"node":{"display_url":"https:\/\/scontent-icn1-1.cdninstagram.com\/first.jpg?x=1"}},
        {"node":{"display_url":"https:\/\/scontent-icn1-1.cdninstagram.com\/second.jpg?x=2"}}
    ]}}</script>`;
    assert.deepEqual(extractImageUrls(html), [
        'https://scontent-icn1-1.cdninstagram.com/first.jpg?x=1',
        'https://scontent-icn1-1.cdninstagram.com/second.jpg?x=2',
    ]);
});

test('extracts the rendered Instagram image element and decodes its source URL', () => {
    const html = `<div><div class="_aagu"><div><img src="https://scontent-gmp1-1.cdninstagram.com/carousel.jpg?first=1&amp;second=2"></div></div></div>`;
    assert.deepEqual(extractImageUrls(html), [
        'https://scontent-gmp1-1.cdninstagram.com/carousel.jpg?first=1&second=2',
    ]);
});

test('uses DOM image URLs as a fallback and ignores unrelated hosts', () => {
    assert.deepEqual(extractImageUrls('<html></html>', [
        'https://example.com/not-an-instagram-image.jpg',
        'https://scontent-nrt1-1.cdninstagram.com/fallback.jpg',
    ]), ['https://scontent-nrt1-1.cdninstagram.com/fallback.jpg']);
});
