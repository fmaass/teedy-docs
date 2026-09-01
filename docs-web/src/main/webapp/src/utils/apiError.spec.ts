import { describe, it, expect } from 'vitest'
import { apiErrorMessage, apiErrorType, apiErrorDetail } from './apiError'

describe('apiErrorMessage', () => {
  it('returns the backend message of a rejected request', () => {
    expect(
      apiErrorMessage({ response: { data: { type: 'ValidationError', message: 'driftgrp is not a valid group' } } }),
    ).toBe('driftgrp is not a valid group')
  })

  it('returns undefined when the failure carries no response body', () => {
    expect(apiErrorMessage(new Error('Network Error'))).toBeUndefined()
    expect(apiErrorMessage({ response: {} })).toBeUndefined()
    expect(apiErrorMessage({ response: { data: {} } })).toBeUndefined()
  })

  it('returns undefined for a blank or non-string message, so the caller keeps its own wording', () => {
    expect(apiErrorMessage({ response: { data: { message: '   ' } } })).toBeUndefined()
    expect(apiErrorMessage({ response: { data: { message: 42 } } })).toBeUndefined()
  })

  it('tolerates null, undefined and primitives', () => {
    expect(apiErrorMessage(null)).toBeUndefined()
    expect(apiErrorMessage(undefined)).toBeUndefined()
    expect(apiErrorMessage('boom')).toBeUndefined()
  })

  it('trims surrounding whitespace', () => {
    expect(apiErrorMessage({ response: { data: { message: '  A step has an invalid target  ' } } })).toBe(
      'A step has an invalid target',
    )
  })
})

describe('apiErrorType', () => {
  it('returns the backend error type', () => {
    expect(apiErrorType({ response: { data: { type: 'InvalidRouteModel' } } })).toBe('InvalidRouteModel')
  })

  it('returns undefined when absent', () => {
    expect(apiErrorType({ response: { data: { message: 'x' } } })).toBeUndefined()
    expect(apiErrorType(null)).toBeUndefined()
  })
})

describe('apiErrorDetail', () => {
  it('joins the type and the message when both are present', () => {
    expect(
      apiErrorDetail({
        response: { data: { type: 'InvalidRouteModel', message: 'A step has an invalid target' } },
      }),
    ).toBe('InvalidRouteModel: A step has an invalid target')
  })

  it('falls back to the message alone when there is no type', () => {
    expect(apiErrorDetail({ response: { data: { message: 'driftgrp is not a valid group' } } })).toBe(
      'driftgrp is not a valid group',
    )
  })

  it('falls back to the type alone when there is no message', () => {
    expect(apiErrorDetail({ response: { data: { type: 'ValidationError' } } })).toBe('ValidationError')
  })

  it('returns undefined when the failure carries neither, so the summary stands alone', () => {
    expect(apiErrorDetail(new Error('Network Error'))).toBeUndefined()
    expect(apiErrorDetail({ response: { data: { type: '  ', message: '  ' } } })).toBeUndefined()
    expect(apiErrorDetail(null)).toBeUndefined()
  })
})
