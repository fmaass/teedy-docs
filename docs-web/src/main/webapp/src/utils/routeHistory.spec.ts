import { describe, it, expect } from 'vitest'
import {
  stepRender,
  transitionSeverity,
  routeStatusSeverity,
  canStartRoute,
  startableModels,
  timeAgo,
} from './routeHistory'

describe('stepRender — the three B2 transition-rendering cases', () => {
  it('end_date == null -> pending (current/open step, no verb)', () => {
    expect(stepRender({ end_date: null, transition: null })).toEqual({ kind: 'pending', transition: null })
    // A null transition with an open step is still pending even if some transition were present
    expect(stepRender({ end_date: null, transition: 'VALIDATED' }).kind).toBe('pending')
  })

  it('end_date set && transition == null -> system-ended/cancelled (neutral)', () => {
    expect(stepRender({ end_date: 1234, transition: null })).toEqual({ kind: 'system', transition: null })
  })

  it('end_date set && transition present -> acted (the verb)', () => {
    expect(stepRender({ end_date: 1234, transition: 'APPROVED' })).toEqual({ kind: 'acted', transition: 'APPROVED' })
    expect(stepRender({ end_date: 1234, transition: 'REJECTED' })).toEqual({ kind: 'acted', transition: 'REJECTED' })
    expect(stepRender({ end_date: 1234, transition: 'VALIDATED' })).toEqual({ kind: 'acted', transition: 'VALIDATED' })
  })
})

describe('transitionSeverity', () => {
  it('approve/validate are success, reject is danger', () => {
    expect(transitionSeverity('APPROVED')).toBe('success')
    expect(transitionSeverity('VALIDATED')).toBe('success')
    expect(transitionSeverity('REJECTED')).toBe('danger')
    expect(transitionSeverity(null)).toBe('info')
  })
})

describe('routeStatusSeverity', () => {
  it('maps each status to a badge severity', () => {
    expect(routeStatusSeverity('DONE')).toBe('success')
    expect(routeStatusSeverity('REJECTED')).toBe('danger')
    expect(routeStatusSeverity('CANCELLED')).toBe('warn')
    expect(routeStatusSeverity('ACTIVE')).toBe('info')
  })
})

describe('canStartRoute — B4 start eligibility', () => {
  const complete = { incomplete: false }
  const incomplete = { incomplete: true }

  it('is false when the document is not writable', () => {
    expect(canStartRoute(false, false, [complete])).toBe(false)
  })

  it('is false when a route is already active on the document', () => {
    expect(canStartRoute(true, true, [complete])).toBe(false)
  })

  it('is false when there is no COMPLETE readable model (only incomplete ones)', () => {
    expect(canStartRoute(true, false, [incomplete])).toBe(false)
    expect(canStartRoute(true, false, [])).toBe(false)
  })

  it('is true only when writable, no active route, and >=1 complete model exists', () => {
    expect(canStartRoute(true, false, [incomplete, complete])).toBe(true)
  })
})

describe('startableModels — incomplete models are excluded from the picker', () => {
  it('drops incomplete:true models', () => {
    const models = [
      { id: 'a', incomplete: false },
      { id: 'b', incomplete: true },
      { id: 'c', incomplete: false },
    ]
    expect(startableModels(models).map((m) => m.id)).toEqual(['a', 'c'])
  })
})

describe('timeAgo', () => {
  const now = 1_000_000_000_000
  it('returns null for a null timestamp', () => {
    expect(timeAgo(null, now)).toBeNull()
  })
  it('buckets recent times as now', () => {
    expect(timeAgo(now - 10_000, now)).toEqual({ unit: 'now', count: 0 })
  })
  it('buckets minutes, hours, days, months, years', () => {
    expect(timeAgo(now - 5 * 60_000, now)).toEqual({ unit: 'minute', count: 5 })
    expect(timeAgo(now - 3 * 3_600_000, now)).toEqual({ unit: 'hour', count: 3 })
    expect(timeAgo(now - 2 * 86_400_000, now)).toEqual({ unit: 'day', count: 2 })
    expect(timeAgo(now - 45 * 86_400_000, now)).toEqual({ unit: 'month', count: 1 })
    expect(timeAgo(now - 400 * 86_400_000, now)).toEqual({ unit: 'year', count: 1 })
  })
})
