import { chromium } from 'playwright';
import { collectProfilePostLinks } from './profile-links.js';

const input = await readInput();
const accounts = Array.isArray(input.accounts) ? input.accounts : [];
const limit = Number.isInteger(input.resultsLimit) && input.resultsLimit > 0 ? input.resultsLimit : 3;
const sessionId = process.env.INSTAGRAM_SESSION_ID?.trim();

if (accounts.length === 0) throw new Error('accounts must contain public Instagram profiles');
if (!sessionId) throw new Error('INSTAGRAM_SESSION_ID is not configured');

const browser = await chromium.launch({ headless: true });
try {
    // Instagram's profile feed is rendered consistently with Playwright's
    // native Chromium fingerprint. Overriding the browser identity caused
    // some authenticated sessions to receive only the profile header.
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

    const results = [];
    for (const account of accounts) {
        const username = typeof account.username === 'string' ? account.username.trim().replace(/^@/, '').toLowerCase() : '';
        const profileUrl = typeof account.profileUrl === 'string' ? account.profileUrl : '';
        if (!username || !profileUrl) continue;

        const page = await context.newPage();
        try {
            await page.goto(profileUrl, { waitUntil: 'domcontentloaded', timeout: 30_000 });
            await page.waitForFunction(() => Array.from(document.querySelectorAll('a[href]'))
                .some((anchor) => /^\/(?:[A-Za-z0-9._]+\/)?(?:p|reel|tv)\/[A-Za-z0-9_-]+/.test(anchor.getAttribute('href') ?? '')),
            undefined, { timeout: 10_000 }).catch(() => undefined);
            const hrefs = await page.locator('a[href]').evaluateAll((anchors) => anchors
                .map((anchor) => anchor.getAttribute('href'))
                .filter((href) => typeof href === 'string'));
            const links = collectProfilePostLinks(hrefs, limit);
            if (links.length === 0) {
                throw new Error(`Instagram profile posts were not rendered for @${username}`);
            }
            results.push({ username, links });
        } finally {
            await page.close();
        }
    }
    process.stdout.write(JSON.stringify({ accounts: results }));
} finally {
    await browser.close();
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
