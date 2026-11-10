import { describe, expect, it } from 'vitest'
import { redirectTarget } from './redirect'

describe('redirectTarget', () => {
  it.each([
    ['/vocabulary', '/vocabulary'],
    ['/grammar?topic=dativ#top', '/grammar?topic=dativ#top'],
    ['/login-help', '/login-help'],
  ])('keeps the in-app path %s', (from, expected) => {
    expect(redirectTarget({ from })).toBe(expected)
  })

  it.each([
    [undefined],
    [null],
    [{}],
    [{ from: 42 }],
    [{ from: 'https://evil.example.com' }],
    [{ from: '//evil.example.com' }],
    [{ from: '/\\evil.example.com' }],
    [{ from: 'vocabulary' }],
    [{ from: '/login' }],
    [{ from: '/login?next=x' }],
    [{ from: '/register' }],
  ])('falls back to the dashboard for %j', (state) => {
    expect(redirectTarget(state)).toBe('/dashboard')
  })
})
