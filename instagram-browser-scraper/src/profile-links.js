const INSTAGRAM_POST_URL = /^(?:https?:\/\/(?:www\.)?instagram\.com)?\/(?:[A-Za-z0-9._]+\/)?(p|reel|tv)\/([A-Za-z0-9_-]+)\/?(?:[?#].*)?$/i;
import { extractPublishedAt, shortcodeFromUrl } from './instagram.js';

/** Scan past pinned posts before stopping at an older chronological grid segment. */
export async function collectProfilePostLinksSince(page, since, readDate) {
    const cutoff = Date.parse(`${since}T00:00:00+09:00`);
    if (!Number.isFinite(cutoff)) throw new Error('since must be a valid YYYY-MM-DD date');
    const seen = new Set(), links = [];
    let older = 0, unchanged = 0, unreadable = 0, dated = 0;
    await waitForProfilePostLinks(page, 1);
    for (let scroll = 0; scroll < 500; scroll += 1) {
        const visible = await page.locator('a[href]').evaluateAll(anchors => anchors.map(a => a.getAttribute('href')));
        const batch = collectProfilePostLinks(visible, Number.MAX_SAFE_INTEGER).filter(link => !seen.has(link.externalId));
        if (batch.length === 0) {
            unchanged += 1;
            if (unchanged >= 10) {
                if (seen.size === 0) throw new Error('No Instagram posts rendered');
                if (dated === 0) throw new Error('Could not read any publication date');
                return links;
            }
        } else unchanged = 0;
        for (const link of batch) {
            seen.add(link.externalId);
            const publishedAt = await readDateWithRetry(readDate, link.url);
            if (!publishedAt) {
                // 한 게시물 때문에 학과 전체가 실패하지 않게 건너뜀. 저장되지 않으므로 다음 실행에서 다시 시도됨
                // 연속 실패는 세션 만료나 화면 구조 변경일 수 있어 학과를 실패 처리함
                if (++unreadable >= 3) throw new Error(`Could not read publication date for ${link.externalId}`);
                console.error(`Skipping ${link.externalId}: publication date unreadable`);
                continue;
            }
            unreadable = 0;
            dated += 1;
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

async function readDateWithRetry(readDate, url) {
    for (let attempt = 0; attempt < 2; attempt += 1) {
        const value = await readDate(url);
        if (value && Number.isFinite(Date.parse(value))) return value;
    }
    return null;
}

export async function readPostPublishedAt(context, url) {
    const page = await context.newPage();
    try {
        const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30_000 });
        if (response?.status() === 429) throw new Error('INSTAGRAM_RATE_LIMIT: pause before retrying');
        await page.locator('meta[property="og:description"]').waitFor({ state: 'attached', timeout: 10_000 });
        // 게시일 time 태그는 하이드레이션 후에 붙음. 없으면 HTML의 taken_at으로 대체
        await page.locator('time[datetime]').first().waitFor({ state: 'attached', timeout: 10_000 }).catch(() => {});
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
