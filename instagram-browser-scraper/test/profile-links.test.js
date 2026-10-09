import test from 'node:test';
import assert from 'node:assert/strict';
import { collectProfilePostLinks, normalizeProfilePostUrl } from '../src/profile-links.js';

test('normalizes Instagram profile post links', () => {
    assert.deepEqual(normalizeProfilePostUrl('/cuk_test/p/FIRST_1/?utm_source=copy_link'), {
        externalId: 'FIRST_1', url: 'https://www.instagram.com/p/FIRST_1/',
    });
    assert.equal(normalizeProfilePostUrl('/accounts/login/'), null);
});

test('keeps first unique profile links up to the limit', () => {
    assert.deepEqual(collectProfilePostLinks([
        '/p/FIRST_1/',
        '/p/FIRST_1/?utm_source=copy_link',
        'https://www.instagram.com/reel/SECOND-2/',
        '/tv/THIRD_3/',
    ], 2), [
        { externalId: 'FIRST_1', url: 'https://www.instagram.com/p/FIRST_1/' },
        { externalId: 'SECOND-2', url: 'https://www.instagram.com/reel/SECOND-2/' },
    ]);
});
