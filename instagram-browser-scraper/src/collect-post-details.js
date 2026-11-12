import { chromium } from 'playwright';
import { extractImageUrls, normalizePostUrl, shortcodeFromUrl } from './instagram.js';

const input = await readInput();
const postUrls = [...new Set((input.postUrls ?? []).map(normalizePostUrl).filter(Boolean))];
const sessionId = process.env.INSTAGRAM_SESSION_ID?.trim();

if (postUrls.length === 0) throw new Error('postUrls must contain at least one Instagram post URL');
if (postUrls.length > 60) throw new Error('At most 60 post URLs can be processed in one local run');
if (!sessionId) throw new Error('INSTAGRAM_SESSION_ID is not configured');

const browser = await chromium.launch({ headless: true });
try {
    const context = await browser.newContext();
    await context.addCookies([{
        name: 'sessionid',
        value: sessionId,
        domain: '.instagram.com',
        path: '/',
        secure: true,
        httpOnly: true,
        sameSite: 'Lax',
    }]);

    const posts = [];
    for (const postUrl of postUrls) {
        const page = await context.newPage();
        try {
            await page.goto(postUrl, { waitUntil: 'domcontentloaded', timeout: 30_000 });
            await page.waitForFunction(() => document.querySelectorAll('._aagu img[src], article img[src]').length > 0,
                undefined, { timeout: 10_000 }).catch(() => undefined);

            const carouselUrls = await collectCarouselImageUrls(page);
            const [html, metadataUrls, caption, publishedAt] = await Promise.all([
                page.content(),
                page.locator('meta[property="og:image"], meta[name="twitter:image"]')
                    .evaluateAll((elements) => elements.map((element) => element.content)),
                page.locator('meta[property="og:description"], meta[name="description"]')
                    .evaluateAll((elements) => elements.map((element) => element.content)
                        .find((content) => typeof content === 'string' && content.trim().length > 0) ?? ''),
                page.locator('time[datetime]').first().getAttribute('datetime').catch(() => null),
            ]);
            const imageUrls = extractImageUrls(html, [...carouselUrls, ...metadataUrls]);
            posts.push({
                postUrl,
                shortcode: shortcodeFromUrl(postUrl),
                imageUrls,
                caption,
                publishedAt,
                status: imageUrls.length > 0 ? 'ok' : 'no_images',
            });
        } catch (error) {
            posts.push({
                postUrl,
                shortcode: shortcodeFromUrl(postUrl),
                imageUrls: [],
                caption: '',
                publishedAt: null,
                status: 'failed',
                error: String(error).slice(0, 300),
            });
        } finally {
            await page.close();
        }
    }
    process.stdout.write(JSON.stringify({ posts }));
} finally {
    await browser.close();
}

async function collectCarouselImageUrls(page) {
    const allUrls = [];
    for (let slide = 0; slide < 20; slide += 1) {
        addUnique(allUrls, await renderedMediaImageUrls(page));

        const next = await visibleNextButton(page);
        if (next === null) break;
        const beforeClick = [...allUrls];
        await next.click();
        try {
            await page.waitForFunction((previous) => Array.from(document.querySelectorAll('._aagu img[src], article img[src]'))
                .map((image) => image.currentSrc || image.src)
                .some((url) => !previous.includes(url)), allUrls, { timeout: 3_000 });
        } catch {
            // The final slide has no new image to render, or Instagram changed its navigation markup.
        }
        addUnique(allUrls, await renderedMediaImageUrls(page));
        if (allUrls.length === beforeClick.length) break;
    }
    return allUrls;
}

async function renderedMediaImageUrls(page) {
    const carouselImages = page.locator('._aagu img[src]');
    const selector = (await carouselImages.count()) > 0 ? '._aagu img[src]' : 'article img[src]';
    return page.locator(selector).evaluateAll((images) => images
        .filter((image) => image.complete && image.naturalWidth >= 200 && image.naturalHeight >= 200)
        .map((image) => image.currentSrc || image.src));
}

async function visibleNextButton(page) {
    const buttons = page.locator('button[aria-label*="Next"], button[aria-label*="다음"]');
    for (let index = 0; index < await buttons.count(); index += 1) {
        const button = buttons.nth(index);
        if (await button.isVisible() && await button.isEnabled()) {
            return button;
        }
    }
    return null;
}

function addUnique(target, urls) {
    for (const url of urls) {
        if (!target.includes(url)) target.push(url);
    }
}

async function readInput() {
    let raw = '';
    for await (const chunk of process.stdin) raw += chunk;
    try {
        return JSON.parse(raw);
    } catch {
        throw new Error('expected JSON input');
    }
}
