const INSTAGRAM_POST_URL = /^(?:https?:\/\/(?:www\.)?instagram\.com)?\/(?:[A-Za-z0-9._]+\/)?(p|reel|tv)\/([A-Za-z0-9_-]+)\/?(?:[?#].*)?$/i;

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
