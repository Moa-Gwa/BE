import test from 'node:test';
import assert from 'node:assert/strict';
import { collectProfilePostLinks, normalizeProfilePostUrl } from '../src/profile-links.js';

test('date scan skips older pinned posts, includes cutoff day and scrolls beyond initial posts', async () => {
    const { collectProfilePostLinksSince } = await import('../src/profile-links.js');
    let scroll = 0;
    const urls = Array.from({length: 28}, (_, i) => `/p/POST_${i}/`);
    const page = {
        locator: () => ({evaluateAll: async () => scroll === 0 ? urls.slice(0, 12) : urls.slice(12)}),
        waitForTimeout: async () => {}, evaluate: async () => {scroll += 1;},
    };
    const links = await collectProfilePostLinksSince(page, '2026-10-06', async url => {
        const i = Number(url.match(/POST_(\d+)/)[1]);
        return i < 3 || i >= 16 ? '2026-10-04T15:00:00Z' : '2026-10-05T15:00:00Z';
    });
    assert.equal(links.length, 13);
    assert.equal(links[0].externalId, 'POST_3');
    assert.equal(links.at(-1).externalId, 'POST_15');
    assert.equal(scroll, 1);
});

test('date scan fails when no publication date can be read at all', async () => {
    const { collectProfilePostLinksSince } = await import('../src/profile-links.js');
    const page = {locator: () => ({evaluateAll: async () => ['/p/UNKNOWN/']}), waitForTimeout: async () => {}, evaluate: async () => {}};
    await assert.rejects(collectProfilePostLinksSince(page, '2026-10-06', async () => null), /publication date/);
});

test('date scan retries once, then skips a single unreadable post and keeps the rest', async () => {
    const { collectProfilePostLinksSince } = await import('../src/profile-links.js');
    const urls = ['/p/NEW_A/', '/p/BROKEN/', '/p/NEW_B/', '/p/OLD_1/', '/p/OLD_2/', '/p/OLD_3/', '/p/OLD_4/'];
    const page = {locator: () => ({evaluateAll: async () => urls}), waitForTimeout: async () => {}, evaluate: async () => {}};
    const calls = {};
    const links = await collectProfilePostLinksSince(page, '2026-11-16', async url => {
        const code = url.match(/\/p\/([^/]+)/)[1];
        calls[code] = (calls[code] ?? 0) + 1;
        if (code === 'BROKEN') return null;
        return code.startsWith('NEW') ? '2026-11-20T00:00:00Z' : '2026-11-01T00:00:00Z';
    });
    assert.deepEqual(links.map(link => link.externalId), ['NEW_A', 'NEW_B']);
    assert.equal(calls.BROKEN, 2);
});

test('date scan fails after three unreadable posts in a row', async () => {
    const { collectProfilePostLinksSince } = await import('../src/profile-links.js');
    const urls = ['/p/OK/', '/p/X1/', '/p/X2/', '/p/X3/'];
    const page = {locator: () => ({evaluateAll: async () => urls}), waitForTimeout: async () => {}, evaluate: async () => {}};
    await assert.rejects(collectProfilePostLinksSince(page, '2026-11-16',
        async url => url.includes('/OK/') ? '2026-11-20T00:00:00Z' : null), /publication date for X3/);
});

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
