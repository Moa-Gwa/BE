import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const script = fileURLToPath(new URL('../src/collect-post-details.js', import.meta.url));
function run(postUrls) {
    return spawnSync(process.execPath, [script], { input: JSON.stringify({ postUrls }), encoding: 'utf8', env: { ...process.env, INSTAGRAM_SESSION_ID: '' } });
}
const urls = n => Array.from({ length: n }, (_, i) => `https://www.instagram.com/p/POST_${i}/`);

test('150 unique post URLs pass the limit check before requiring a session', () => {
    const result = run(urls(150));
    assert.match(result.stderr, /INSTAGRAM_SESSION_ID is not configured/);
    assert.doesNotMatch(result.stderr, /At most 150/);
});
test('151 unique post URLs are rejected without opening a browser', () => {
    const result = run(urls(151));
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /At most 150 post URLs/);
});
test('duplicate URLs do not consume the 150 unique URL allowance', () => {
    const result = run([...urls(150), ...urls(150)]);
    assert.match(result.stderr, /INSTAGRAM_SESSION_ID is not configured/);
    assert.doesNotMatch(result.stderr, /At most 150/);
});
