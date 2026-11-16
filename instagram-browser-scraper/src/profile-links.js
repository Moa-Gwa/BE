const INSTAGRAM_POST_URL = /^(?:https?:\/\/(?:www\.)?instagram\.com)?\/(?:[A-Za-z0-9._]+\/)?(p|reel|tv)\/([A-Za-z0-9_-]+)\/?(?:[?#].*)?$/i;
import { extractPublishedAt, shortcodeFromUrl } from './instagram.js';

/** Scan past pinned posts before stopping at an older chronological grid segment. */
export async function collectProfilePostLinksSince(page, since, readDate) {
    const cutoff = Date.parse(`${since}T00:00:00+09:00`);
    if (!Number.isFinite(cutoff)) throw new Error('since must be a valid YYYY-MM-DD date');
    const seen = new Set(), links = [];
    let older = 0, unchanged = 0;
    await waitForProfilePostLinks(page, 1);
    for (let scroll = 0; scroll < 500; scroll += 1) {
        const visible = await page.locator('a[href]').evaluateAll(anchors => anchors.map(a => a.getAttribute('href')));
        const batch = collectProfilePostLinks(visible, Number.MAX_SAFE_INTEGER).filter(link => !seen.has(link.externalId));
        if (batch.length === 0) {
            unchanged += 1;
            if (unchanged >= 10) {
                if (seen.size === 0) throw new Error('No Instagram posts rendered');
                return links;
            }
        } else unchanged = 0;
        for (const link of batch) {
            seen.add(link.externalId);
            const publishedAt = await readDate(link.url);
            if (!publishedAt || !Number.isFinite(Date.parse(publishedAt))) {
                throw new Error(`Could not read publication date for ${link.externalId}`);
            }
            if (Date.parse(publishedAt) >= cutoff) {
                links.push(link);
                older = 0;
            } else if (seen.size > 3) older += 1;
            if (older >= 3) return links;
        }
        await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
        await page.waitForTimeout(1500);
    }
    throw new Error('Profile scan exceeded safety timeout before reaching the requested date');
}

export async function readPostPublishedAt(context, url) {
    const page = await context.newPage();
    try {
        const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30_000 });
        if (response?.status() === 429) throw new Error('INSTAGRAM_RATE_LIMIT: pause before retrying');
        await page.locator('meta[property="og:description"]').waitFor({ state: 'attached', timeout: 10_000 });
        const datetime = await page.evaluate(() => document.querySelector('time[datetime]')?.getAttribute('datetime'));
        return extractPublishedAt(datetime, await page.content(), shortcodeFromUrl(url));
    } finally {
        await page.close();
        await new Promise(resolve => setTimeout(resolve, 2000));
    }
}

export function normalizeProfilePostUrl(value) {
    if (typeof value !== 'string') return null;
    const match = value.trim().replaceAll('\\/', '/').match(INSTAGRAM_POST_URL);
    if (!match) return null;
    return {
        externalId: match[2],
        url: `https://www.instagram.com/${match[1].toLowerCase()}/${match[2]}/`,
    };
}

export function collectProfilePostLinks(hrefs, limit) {
    const links = [];
    const seen = new Set();
    for (const href of hrefs) {
        const link = normalizeProfilePostUrl(href);
        if (link === null || seen.has(link.externalId)) continue;
        seen.add(link.externalId);
        links.push(link);
        if (links.length >= limit) break;
    }
    return links;
}

/** Wait through hydration, which can briefly remove links that were already visible. */
export async function waitForProfilePostLinks(page, limit) {
    for (let attempt = 0; attempt < 60; attempt += 1) {
        const hrefs = await page.locator('a[href]').evaluateAll((anchors) => anchors
            .map((anchor) => anchor.getAttribute('href'))
            .filter((href) => typeof href === 'string')).catch(() => []);
        const links = collectProfilePostLinks(hrefs, limit);
        if (links.length > 0) return links;
        await page.waitForTimeout(500);
    }
    return [];
}
