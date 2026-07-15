'use strict';

// Cookie handling for the Teedy importer, extracted so it can be unit-tested (main.js starts an
// interactive wizard on load and cannot be required directly).

// Splits a combined Set-Cookie header value (the single-string fallback for runtimes without
// getSetCookie()) into individual cookies. A naive split on ',' would break the Expires date
// ("Expires=Thu, 01 Jan ..."); split only on a comma that begins a new "<name>=" pair.
const splitCombinedSetCookie = (value) => value.split(/,(?=\s*[^=;,\s]+=)/);

// Creates a minimal cookie jar: captures Set-Cookie from responses (e.g. the auth_token session cookie
// and the csrf_token cookie returned by /api/user/login) and replays them on later requests.
const createCookieJar = () => {
  const cookies = {};

  const storeCookies = (response) => {
    // Node's fetch exposes multiple Set-Cookie headers via getSetCookie(); older runtimes collapse them
    // into one comma-joined header, which we split back apart so EVERY cookie (auth_token AND csrf_token)
    // is captured, not just the first.
    const setCookies = typeof response.headers.getSetCookie === 'function'
      ? response.headers.getSetCookie()
      : (response.headers.get('set-cookie') ? splitCombinedSetCookie(response.headers.get('set-cookie')) : []);
    for (const raw of setCookies) {
      const pair = raw.split(';')[0];
      const eq = pair.indexOf('=');
      if (eq > 0) {
        cookies[pair.slice(0, eq).trim()] = pair.slice(eq + 1).trim();
      }
    }
  };

  const cookieHeader = () => Object.keys(cookies)
    .map((name) => name + '=' + cookies[name])
    .join('; ');

  // CSRF headers to submit so the importer keeps working once CSRF enforcement is enabled. The importer
  // sends no Origin/Referer, so the header token is its sole CSRF proof — which is why capturing the
  // cookies above must be reliable. The importer normally authenticates by username/password (session
  // mechanism => csrf_token); the __Host-csrf_proxy branch only applies if it runs behind a
  // trusted-header proxy, in which case that token is forwarded too.
  const csrfHeaders = () => {
    const headers = {};
    if (cookies['csrf_token']) {
      headers['X-Csrf-Token'] = cookies['csrf_token'];
    }
    if (cookies['__Host-csrf_proxy']) {
      headers['X-Csrf-Proxy'] = cookies['__Host-csrf_proxy'];
    }
    return headers;
  };

  return { cookies, storeCookies, cookieHeader, csrfHeaders };
};

module.exports = { splitCombinedSetCookie, createCookieJar };
