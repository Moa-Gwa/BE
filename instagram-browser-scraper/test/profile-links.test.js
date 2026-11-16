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

test('waits through empty pages and hydration navigation before collecting links', async () => {
    const { waitForProfilePostLinks } = await import('../src/profile-links.js');
    let polls = 0, waits = 0;
    const page = {
        locator: () => ({ evaluateAll: async () => {
            polls += 1;
            if (polls === 1) throw new Error('Execution context was destroyed');
            return polls === 2 ? [] : ['/p/READY_1/', '/p/READY_1/', '/p/READY_2/'];
        } }),
        waitForTimeout: async () => { waits += 1; },
    };
    assert.deepEqual(await waitForProfilePostLinks(page, 1), [{ externalId: 'READY_1', url: 'https://www.instagram.com/p/READY_1/' }]);
    assert.equal(polls, 3);
    assert.equal(waits, 2);
});

test('stops polling an unavailable profile after the bounded render wait', async () => {
    const { waitForProfilePostLinks } = await import('../src/profile-links.js');
    let waits = 0;
    const page = { locator: () => ({ evaluateAll: async () => [] }), waitForTimeout: async () => { waits += 1; } };
    assert.deepEqual(await waitForProfilePostLinks(page, 3), []);
    assert.equal(waits, 60);
});
