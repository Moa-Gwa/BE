const INSTAGRAM_POST_URL = /^https?:\/\/(?:www\.)?instagram\.com\/(p|reel|tv)\/([A-Za-z0-9_-]+)\/?(?:[?#].*)?$/i;
const DISPLAY_URL = /"(?:display_url|displayUrl|image_url|imageUrl)"\s*:\s*"((?:\\.|[^"\\])*)"/g;
const RENDERED_MEDIA_IMAGE = /<div\b[^>]*class=["'][^"']*\b_aagu\b[^"']*["'][^>]*>[\s\S]*?<img\b[^>]*\bsrc=(?:"([^"]+)"|'([^']+)')/gi;

export function normalizePostUrl(value) {
    if (typeof value !== 'string') return null;
    const match = value.trim().match(INSTAGRAM_POST_URL);
    if (!match) return null;
    return `https://www.instagram.com/${match[1].toLowerCase()}/${match[2]}/`;
}

export function shortcodeFromUrl(value) {
    return normalizePostUrl(value)?.split('/').at(-2) ?? null;
}

/**
 * Pulls canonical image fields from the page's embedded post payload. Instagram changes its page
 * markup frequently, so metadata passed from the rendered document is included as a safe fallback.
 */
export function extractImageUrls(html, renderedImageUrls = []) {
    const urls = [];
    for (const url of renderedImageUrls) {
        addUrl(urls, url);
    }
    let renderedMatch;
    while ((renderedMatch = RENDERED_MEDIA_IMAGE.exec(html)) !== null) {
        addUrl(urls, renderedMatch[1] ?? renderedMatch[2]);
    }
    let match;
    while ((match = DISPLAY_URL.exec(html)) !== null) {
        addUrl(urls, decodeJsonString(match[1]));
    }
    return [...new Set(urls)];
}

function decodeJsonString(value) {
    try {
        return JSON.parse(`"${value}"`);
    } catch {
        return value;
    }
}

function decodeUrl(value) {
    return value
        .replaceAll('\\u0026', '&')
        .replaceAll('\\/', '/')
        .replaceAll('%5C/', '/')
        .replaceAll('&amp;', '&')
        .replaceAll('\\\\', '\\');
}

function addUrl(urls, value) {
    if (typeof value !== 'string') return;
    const url = decodeUrl(value).trim();
    try {
        const parsed = new URL(url);
        if (parsed.protocol === 'https:' && /(^|\.)(cdninstagram\.com|fbcdn\.net)$/i.test(parsed.hostname)) {
            urls.push(parsed.toString());
        }
    } catch {
        // Ignore malformed values in an Instagram page payload.
    }
}
