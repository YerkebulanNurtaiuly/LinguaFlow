import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import {
  getApiProblem,
  getErrorMessage,
  getFieldErrors,
  hasErrorCode,
} from './errors'
import type { ApiProblem } from './types'

const validationProblem: ApiProblem = {
  type: 'about:blank',
  title: 'Validation failed',
  status: 400,
  detail: 'One or more fields are invalid',
  instance: '/api/vocabulary',
  code: 'VALIDATION_FAILED',
  correlationId: 'abc-123',
  timestamp: '2026-11-09T10:00:00Z',
  errors: [
    { field: 'lemma', message: 'must not be blank' },
    { field: 'lemma', message: 'second message is ignored' },
    { field: 'level', message: 'must be at most 10' },
  ],
}

function axiosErrorWith(data: unknown, status = 400): AxiosError {
  const response = {
    data,
    status,
    statusText: '',
    headers: {},
    config: { headers: new AxiosHeaders() },
  } as AxiosResponse
  return new AxiosError(
    'Request failed',
    'ERR_BAD_REQUEST',
    undefined,
    undefined,
    response,
  )
}

describe('API error helpers', () => {
  it('extracts problem details from a failed request', () => {
    const error = axiosErrorWith(validationProblem)

    expect(getApiProblem(error)).toEqual(validationProblem)
    expect(hasErrorCode(error, 'VALIDATION_FAILED')).toBe(true)
    expect(hasErrorCode(error, 'NOT_FOUND')).toBe(false)
    expect(getErrorMessage(error)).toBe('One or more fields are invalid')
  })

  it('maps validation errors to the first message per field', () => {
    expect(getFieldErrors(axiosErrorWith(validationProblem))).toEqual({
      lemma: 'must not be blank',
      level: 'must be at most 10',
    })
  })

  it('ignores responses that are not problem details', () => {
    const error = axiosErrorWith('<html>Bad Gateway</html>', 502)

    expect(getApiProblem(error)).toBeUndefined()
    expect(getFieldErrors(error)).toEqual({})
    expect(getErrorMessage(error)).toBe(
      'Something went wrong. Please try again.',
    )
  })

  it('ignores non-HTTP errors', () => {
    expect(getApiProblem(new Error('boom'))).toBeUndefined()
    expect(getErrorMessage(new Error('boom'), 'Offline')).toBe('Offline')
  })
})
