import type { Route, RouteStep } from '../api/route'
import type { RouteModelListItem } from '../api/routeModel'

// Pure helpers for the document Workflow tab. Kept out of the component so the transition-rendering
// and start-eligibility rules are unit-testable without mounting.

// How a step's outcome should render in the history table. The i18n key is resolved by the caller.
//   'pending'  -> end_date == null: the step is the current/open one (no verb, awaiting action)
//   'system'   -> end_date != null && transition == null: system-ended (route cancelled/halted,
//                 nobody acted on this step) — a neutral badge
//   'acted'    -> a real user-action transition (VALIDATED/APPROVED/REJECTED) — the verb, colored
export type StepRenderKind = 'pending' | 'system' | 'acted'

export interface StepRender {
  kind: StepRenderKind
  // The transition verb (only for kind 'acted'); null otherwise.
  transition: RouteStep['transition']
}

/**
 * Classify how a step should render, per the B2-adjudicated rule:
 *   end_date == null                       -> current/pending
 *   end_date != null && transition == null -> neutral system-ended/cancelled
 *   else                                   -> the transition verb (color-coded by the caller)
 */
export function stepRender(step: Pick<RouteStep, 'end_date' | 'transition'>): StepRender {
  if (step.end_date == null) {
    return { kind: 'pending', transition: null }
  }
  if (step.transition == null) {
    return { kind: 'system', transition: null }
  }
  return { kind: 'acted', transition: step.transition }
}

// PrimeVue-tag severity for an acted transition verb.
export function transitionSeverity(transition: RouteStep['transition']): 'success' | 'danger' | 'info' {
  switch (transition) {
    case 'APPROVED':
    case 'VALIDATED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    default:
      return 'info'
  }
}

// PrimeVue-tag severity for a route status badge.
export function routeStatusSeverity(status: Route['status']): 'success' | 'danger' | 'warn' | 'info' {
  switch (status) {
    case 'DONE':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'CANCELLED':
      return 'warn'
    case 'ACTIVE':
    default:
      return 'info'
  }
}

/**
 * Whether the Start-route control may be shown at all (B4): the doc must be writable, no route may
 * be active on it, and at least one COMPLETE readable model must exist. The model list deliberately
 * includes incomplete models (unresolved targets) which `start` rejects — so completeness is
 * required here. The tab still renders for history/current-step even when this is false.
 */
export function canStartRoute(
  writable: boolean,
  hasActiveRoute: boolean,
  models: Pick<RouteModelListItem, 'incomplete'>[],
): boolean {
  if (!writable || hasActiveRoute) return false
  return models.some((m) => !m.incomplete)
}

// The models offered in the start picker: complete models only (incomplete ones would be rejected
// InvalidRouteModel by the backend). Excluding them is cleaner than disabling+erroring.
export function startableModels<T extends Pick<RouteModelListItem, 'incomplete'>>(models: T[]): T[] {
  return models.filter((m) => !m.incomplete)
}

// Relative "time ago" for a timestamp (ms). Returns a coarse bucket key + count for i18n, so the
// component owns the wording/pluralization. null timestamp -> null (render nothing).
export interface TimeAgo {
  unit: 'now' | 'minute' | 'hour' | 'day' | 'month' | 'year'
  count: number
}

export function timeAgo(ts: number | null, now: number = Date.now()): TimeAgo | null {
  if (ts == null) return null
  const secs = Math.max(0, Math.floor((now - ts) / 1000))
  if (secs < 45) return { unit: 'now', count: 0 }
  const mins = Math.floor(secs / 60)
  if (mins < 60) return { unit: 'minute', count: Math.max(1, mins) }
  const hours = Math.floor(mins / 60)
  if (hours < 24) return { unit: 'hour', count: hours }
  const days = Math.floor(hours / 24)
  if (days < 30) return { unit: 'day', count: days }
  const months = Math.floor(days / 30)
  if (months < 12) return { unit: 'month', count: months }
  return { unit: 'year', count: Math.floor(months / 12) }
}
