'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { splitCombinedSetCookie, createCookieJar } = require('./cookies');

// A fake fetch Response.headers whose getSetCookie() is ABSENT, so the jar exercises the combined
// single-string Set-Cookie fallback path (older runtimes / polyfills).
const headersWithoutGetSetCookie = (combined) => ({
  get: (name) => (name.toLowerCase() === 'set-cookie' ? combined : null),
});

// A fake headers object exposing getSetCookie() returning an array of Set-Cookie header lines.
const headersWithGetSetCookie = (list) => ({
  get: () => null,
  getSetCookie: () => list,
});

test('splitCombinedSetCookie splits multiple cookies including an Expires comma', () => {
  const combined =
    'auth_token=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT, csrf_token=CCC; Path=/; Secure';
  const parts = splitCombinedSetCookie(combined);
  assert.strictEqual(parts.length, 2, 'must split into exactly two cookies, not on the Expires comma');
  assert.ok(parts[0].startsWith('auth_token='));
  assert.ok(parts[1].trim().startsWith('csrf_token=CCC'));
});

test('storeCookies captures BOTH cookies on the non-getSetCookie fallback path', () => {
  const jar = createCookieJar();
  jar.storeCookies({
    headers: headersWithoutGetSetCookie(
      'auth_token=AAA; Path=/; Secure; SameSite=Lax, csrf_token=BBB; Path=/; Secure; SameSite=Lax',
    ),
  });
  assert.strictEqual(jar.cookies['auth_token'], 'AAA');
  assert.strictEqual(jar.cookies['csrf_token'], 'BBB', 'csrf_token must be captured, not dropped');
});

test('storeCookies captures cookies via getSetCookie() when available', () => {
  const jar = createCookieJar();
  jar.storeCookies({
    headers: headersWithGetSetCookie([
      'auth_token=AAA; Path=/; Secure',
      'csrf_token=BBB; Path=/; Secure',
    ]),
  });
  assert.strictEqual(jar.cookies['auth_token'], 'AAA');
  assert.strictEqual(jar.cookies['csrf_token'], 'BBB');
});

test('csrfHeaders submits the session token from csrf_token', () => {
  const jar = createCookieJar();
  jar.storeCookies({ headers: headersWithGetSetCookie(['csrf_token=SESS; Path=/']) });
  assert.deepStrictEqual(jar.csrfHeaders(), { 'X-Csrf-Token': 'SESS' });
});

test('csrfHeaders forwards the proxy token when running behind a trusted-header proxy', () => {
  const jar = createCookieJar();
  jar.storeCookies({
    headers: headersWithGetSetCookie([
      'csrf_token=SESS; Path=/',
      '__Host-csrf_proxy=PROXY; Path=/; Secure',
    ]),
  });
  assert.deepStrictEqual(jar.csrfHeaders(), {
    'X-Csrf-Token': 'SESS',
    'X-Csrf-Proxy': 'PROXY',
  });
});

test('csrfHeaders is empty when no CSRF cookie has been seen', () => {
  const jar = createCookieJar();
  assert.deepStrictEqual(jar.csrfHeaders(), {});
});
