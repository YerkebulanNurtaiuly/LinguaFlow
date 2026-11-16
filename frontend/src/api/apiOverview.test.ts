import { describe, expect, it } from 'vitest'
import spec from '../../openapi.json?raw'
import overview from '../../../docs/technical-specification/api.md?raw'

/**
 * DOC-01: the technical specification lists every API operation. This keeps that list in
 * step with the OpenAPI snapshot, so an added or removed endpoint updates the docs too.
 * When it fails, run `npm run docs:api` (scripts/api-overview.mjs) and commit api.md.
 */
const METHODS = new Set(['get', 'post', 'put', 'patch', 'delete'])

function specOperations(): string[] {
  const { paths } = JSON.parse(spec) as {
    paths: Record<string, Record<string, unknown>>
  }
  return Object.entries(paths).flatMap(([path, operations]) =>
    Object.keys(operations)
      .filter((method) => METHODS.has(method))
      .map((method) => `${method.toUpperCase()} ${path}`),
  )
}

function documentedOperations(): string[] {
  return [
    ...overview.matchAll(/^\| (GET|POST|PUT|PATCH|DELETE) \| `([^`]+)` \|/gm),
  ].map((match) => `${match[1]!} ${match[2]!}`)
}

describe('API overview in docs/technical-specification/api.md', () => {
  it('lists every operation of the OpenAPI snapshot exactly once', () => {
    const documented = documentedOperations()
    expect(new Set(documented).size).toBe(documented.length)
    expect([...documented].sort()).toEqual(specOperations().sort())
  })
})
