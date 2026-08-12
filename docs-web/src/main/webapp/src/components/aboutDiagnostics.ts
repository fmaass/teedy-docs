import type { AppInfo, AppDiagnostics } from '../api/app'

// #275: the About dialog's admin-only "report a bug / diagnostics" affordance. The block and the
// GitHub new-issue URL are built here as pure functions so their exact wire format and encoding are
// unit-tested independently of the component (the aboutHighlights precedent, BL-019).

// The repo the "Report a bug" button files against. The owner/repo pair is already hardcoded for the
// releases and commit links in AboutDialog.vue; this keeps that single source.
export const ISSUE_TRACKER_REPO = 'fmaass/teedy-docs'

const UNKNOWN = 'unknown'

// The deployment auth-mode reported in the block: OIDC wins over reverse-proxy header auth, and an
// instance with neither is running Teedy's own internal accounts. Mirrors the server's own
// precedence (oidc_enabled is the DB-or-property accessor; header_authentication_enabled is the
// docs.header_authentication property).
export function authMode(appInfo: AppInfo | null | undefined): string {
  if (appInfo?.oidc_enabled) return 'OIDC'
  if (appInfo?.header_authentication_enabled) return 'header'
  return 'internal'
}

// The environment block, in the Markdown a maintainer pastes into (or the button pre-fills on) a
// GitHub issue. version/commit come from the already-loaded GET /app; the six stack fields come from
// the admin-only GET /app/diagnostics. Any field missing (an older server, or diagnostics not yet
// loaded) degrades to "unknown" rather than blanking the line.
export function buildDiagnosticsBlock(
  appInfo: AppInfo | null | undefined,
  diagnostics: AppDiagnostics | null | undefined,
): string {
  const version = appInfo?.current_version ?? UNKNOWN
  const commit = appInfo?.commit_id ?? UNKNOWN
  const jetty = diagnostics?.jetty_version ?? UNKNOWN
  const javaVersion = diagnostics?.java_version ?? UNKNOWN
  const javaVendor = diagnostics?.java_vendor ?? UNKNOWN
  const osName = diagnostics?.os_name ?? UNKNOWN
  const osVersion = diagnostics?.os_version ?? UNKNOWN
  const osArch = diagnostics?.os_arch ?? UNKNOWN
  return [
    '### Environment',
    `- Teedy version: ${version}`,
    `- Build commit: ${commit}`,
    `- Jetty: ${jetty}`,
    `- Java: ${javaVersion} (${javaVendor})`,
    `- OS: ${osName} ${osVersion} (${osArch})`,
    `- Auth mode: ${authMode(appInfo)}`,
  ].join('\n')
}

// The human half of the pre-filled issue: a prompt to describe the problem and a
// steps/expected/actual scaffold. GitHub issues on this repo are written in English, so this text is
// intentionally not localized (unlike the button labels and toasts, which the operator sees in the
// UI). The diagnostics block is appended below it.
const REPORT_SCAFFOLD = [
  'Describe the problem: what you did and what went wrong.',
  '',
  '### Steps to reproduce',
  '1. ',
  '2. ',
  '',
  '### Expected result',
  '',
  '### Actual result',
  '',
].join('\n')

export function buildReportBody(block: string): string {
  return `${REPORT_SCAFFOLD}\n${block}\n`
}

// The prefilled GitHub new-issue URL: the bug label plus the URL-encoded body. Opened in a new tab.
export function buildReportUrl(block: string): string {
  const body = encodeURIComponent(buildReportBody(block))
  return `https://github.com/${ISSUE_TRACKER_REPO}/issues/new?labels=bug&body=${body}`
}
