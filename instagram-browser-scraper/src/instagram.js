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

export function extractCaption(caption, description = '') {
    if (typeof caption === 'string' && caption.trim()) return caption.trim();
    const metadata = description.trim();
    const prefix = /^(?:[\d,.]+[KMB]? likes?(?:, [\d,.]+[KMB]? comments?)? - )?[\w.]+ on [A-Za-z]+ \d{1,2}, \d{4}/i;
    const match = metadata.match(prefix);
    if (!match) return '';
    const content = metadata.slice(match[0].length);
    if (!content.startsWith(': "')) return '';
    return content.slice(3).replace(/"\.?$/, '').trim();
}

/**
 * Returns the exact publication time. The og:description date is not used: it is rendered in
 * Instagram's own time zone, so it can be a day off from the Korean date and has no time of day.
 */
export function extractPublishedAt(datetime, html = '', shortcode = '') {
    if (datetime && Number.isFinite(Date.parse(datetime))) return new Date(datetime).toISOString();
    const takenAt = takenAtFromHtml(html, shortcode);
    return takenAt ? new Date(takenAt * 1000).toISOString() : null;
}

/** Reads the post's `taken_at` (Unix seconds) closest to its own shortcode in the embedded payload. */
function takenAtFromHtml(html, shortcode) {
    if (typeof html !== 'string' || !html) return null;
    const anchors = [];
    if (shortcode) {
        for (const match of html.matchAll(new RegExp(`"(?:code|shortcode)"\\s*:\\s*"${escapeRegExp(shortcode)}"`, 'g'))) {
            anchors.push(match.index);
        }
        if (anchors.length === 0) return null;
    }
    const values = [...html.matchAll(/"taken_at(?:_timestamp)?"\s*:\s*(\d{10})\b/g)]
        .map(match => ({ index: match.index, seconds: Number(match[1]) }));
    if (!anchors.length) return values[0]?.seconds ?? null;
    // The post's own object usually lists taken_at after its code; fall back to the nearest one before it.
    let best = null;
    for (const anchor of anchors) {
        const after = values.find(value => value.index > anchor && value.index - anchor <= 20_000);
        const before = values.filter(value => value.index < anchor && anchor - value.index <= 20_000).at(-1);
        const candidate = after ? { ...after, rank: 0, distance: after.index - anchor }
            : before ? { ...before, rank: 1, distance: anchor - before.index } : null;
        if (candidate && (!best || candidate.rank < best.rank
            || (candidate.rank === best.rank && candidate.distance < best.distance))) best = candidate;
    }
    return best?.seconds ?? null;
}

function escapeRegExp(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
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
