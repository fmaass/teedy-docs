import axios from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'

const api = axios.create({
  baseURL: 'api',
  withCredentials: true,
})

/**
 * Reads a named non-HttpOnly cookie value, or null when absent. `__Host-`-prefixed cookies are still
 * readable via document.cookie (the prefix only constrains how the browser SETS/sends them).
 */
function readCookie(name: string): string | null {
  const escaped = name.replace(/[.*+?^${}()|[\]\\-]/g, '\\$&')
  const match = document.cookie.match(new RegExp('(?:^|;\\s*)' + escaped + '=([^;]*)'))
  return match ? decodeURIComponent(match[1]) : null
}

// The single sanctioned SPA CSRF touch: mirror each mechanism's CSRF cookie into its request header on
// every request. A cross-site page cannot read these host-only cookies, so it cannot forge the headers —
// that is the CSRF proof the backend checks (report-only in this release). Both are sent because the SPA
// does not know which mechanism authenticated the request (session cookie vs. trusted proxy); the backend
// validates whichever matches the request's proven mechanism.
function attachCsrfHeaders(config: InternalAxiosRequestConfig) {
  const sessionToken = readCookie('csrf_token')
  if (sessionToken) {
    config.headers.set('X-Csrf-Token', sessionToken)
  }
  const proxyToken = readCookie('__Host-csrf_proxy')
  if (proxyToken) {
    config.headers.set('X-Csrf-Proxy', proxyToken)
  }
}

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  attachCsrfHeaders(config)
  return config
})

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const status = error.response?.status
    const config = error.config as (InternalAxiosRequestConfig & { _csrfRetried?: boolean }) | undefined

    // CSRF recoverable-mismatch (H3, active once enforcement is on): the server rejected with a
    // CsrfError and re-set the expected cookie. Re-read it and retry the request exactly once —
    // reject-before-handler makes the single retry safe even for non-idempotent requests.
    if (
      status === 403 &&
      error.response?.data?.type === 'CsrfError' &&
      config &&
      !config._csrfRetried
    ) {
      config._csrfRetried = true
      attachCsrfHeaders(config)
      return api.request(config)
    }

    // 401 = not authenticated -> bounce to login. 403 = authenticated but not
    // authorised -> the caller renders "access denied" and stays on the page
    // (redirecting on 403 turned a permission error into a login bounce).
    if (status === 401) {
      window.location.hash = '#/login'
    }
    return Promise.reject(error)
  },
)

export default api
