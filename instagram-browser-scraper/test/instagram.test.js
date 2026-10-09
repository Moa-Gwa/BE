import test from 'node:test';
import assert from 'node:assert/strict';
import { extractImageUrls, normalizePostUrl, shortcodeFromUrl } from '../src/instagram.js';

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
