// Rewrites the operation tables in docs/technical-specification/api.md from openapi.json
// (DOC-01). Run with `npm run docs:api` after the API changes; apiOverview.test.ts fails
// until the two agree.
import { readFileSync, writeFileSync } from 'node:fs'

const specFile = new URL('../openapi.json', import.meta.url)
const docFile = new URL(
  '../../docs/technical-specification/api.md',
  import.meta.url,
)

// Areas in the order a reader meets them; new tags are appended
const ORDER = [
  'Auth',
  'Profile',
  'Languages',
  'Vocabulary',
  'Tags',
  'Grammar',
  'Study sessions',
  'Flashcards',
  'Reviews',
  'Exercises',
  'Practice',
  'Progress',
  'AI jobs',
  'AI usage',
  'Monitoring',
  'Actuator',
]
const METHODS = ['get', 'post', 'put', 'patch', 'delete']

const spec = JSON.parse(readFileSync(specFile, 'utf8'))
const byTag = new Map()
for (const [path, operations] of Object.entries(spec.paths)) {
  for (const [method, operation] of Object.entries(operations)) {
    if (!METHODS.includes(method)) continue
    const tag = operation.tags?.[0] ?? 'Other'
    const isPublic =
      Array.isArray(operation.security) && operation.security.length === 0
    const summary = (operation.summary ?? '').replaceAll('|', '/')
    if (!byTag.has(tag)) byTag.set(tag, [])
    byTag
      .get(tag)
      .push({ method, path, text: summary + (isPublic ? ' _(public)_' : '') })
  }
}

const tags = [
  ...ORDER.filter((tag) => byTag.has(tag)),
  ...[...byTag.keys()].filter((tag) => !ORDER.includes(tag)).sort(),
]
let tables = ''
for (const tag of tags) {
  tables += `\n### ${tag}\n\n| Method | Path | What it does |\n|---|---|---|\n`
  const rows = byTag
    .get(tag)
    .sort(
      (a, b) =>
        a.path.localeCompare(b.path) ||
        METHODS.indexOf(a.method) - METHODS.indexOf(b.method),
    )
  for (const row of rows) {
    tables += `| ${row.method.toUpperCase()} | \`${row.path}\` | ${row.text} |\n`
  }
}

const doc = readFileSync(docFile, 'utf8')
const updated = doc.replace(
  /<!-- operations:start -->[\s\S]*<!-- operations:end -->/,
  `<!-- operations:start -->\n${tables}\n<!-- operations:end -->`,
)
if (updated === doc) {
  console.log('api.md is up to date')
} else {
  writeFileSync(docFile, updated)
  console.log('api.md updated')
}
