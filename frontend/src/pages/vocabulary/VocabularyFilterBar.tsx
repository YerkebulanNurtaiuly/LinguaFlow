import { Search, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Button, Field, Input, Select } from '@/components/ui'
import { CEFR_LEVELS } from '@/languages/languagesApi'
import {
  KNOWLEDGE_STATUSES,
  PARTS_OF_SPEECH,
  VOCABULARY_SORTS,
  type TagWithCount,
  type VocabularyQuery,
} from '@/vocabulary/vocabularyApi'
import { hasFilters, PART_OF_SPEECH_LABELS } from './vocabularyFilters'

const STATUS_LABELS = {
  NEW: 'New',
  LEARNING: 'Learning',
  LEARNED: 'Learned',
  MASTERED: 'Mastered',
} as const

/** How long typing pauses before the search runs. */
export const SEARCH_DELAY_MS = 300

interface VocabularyFilterBarProps {
  query: VocabularyQuery
  /** Applies a change; filter changes go back to the first page */
  onChange: (changes: Partial<VocabularyQuery>) => void
  onClear: () => void
  /** Undefined while the tags load or if they failed: the tag filter is hidden */
  tags?: TagWithCount[]
}

export function VocabularyFilterBar({
  query,
  onChange,
  onClear,
  tags,
}: VocabularyFilterBarProps) {
  const [search, setSearch] = useState(query.q ?? '')
  const [syncedQ, setSyncedQ] = useState(query.q)
  // The search this box last sent; its echo from the URL must not reset newer typing
  const [sentQ, setSentQ] = useState(query.q)

  // The URL changed from outside (Clear filters, back button): show its search text
  if (query.q !== syncedQ) {
    setSyncedQ(query.q)
    if (query.q !== sentQ) setSearch(query.q ?? '')
  }

  useEffect(() => {
    const value = search.trim() || undefined
    if (value === query.q) return
    const timer = setTimeout(() => {
      setSentQ(value)
      onChange({ q: value })
    }, SEARCH_DELAY_MS)
    return () => clearTimeout(timer)
  }, [search, query.q, onChange])

  return (
    <div className="space-y-4 rounded-xl border border-border bg-surface p-4">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {/* The icon sits outside Field so the label targets the input itself;
            the input (h-10) is the field's last element, so bottom-3 centres it */}
        <div className="relative sm:col-span-2 lg:col-span-3">
          <Field label="Search">
            <Input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Word or translation, e.g. Strasse"
              className="pl-9"
              maxLength={200}
            />
          </Field>
          <Search
            className="pointer-events-none absolute bottom-3 left-3 size-4 text-fg-subtle"
            aria-hidden="true"
          />
        </div>
        <Field label="Status">
          <Select
            value={query.status ?? ''}
            placeholder="Any status"
            options={KNOWLEDGE_STATUSES.map((status) => ({
              value: status,
              label: STATUS_LABELS[status],
            }))}
            onChange={(event) =>
              onChange({
                status: (event.target.value ||
                  undefined) as VocabularyQuery['status'],
              })
            }
          />
        </Field>
        <Field label="Level">
          <Select
            value={query.cefrLevel ?? ''}
            placeholder="Any level"
            options={CEFR_LEVELS.map((level) => ({
              value: level,
              label: level,
            }))}
            onChange={(event) =>
              onChange({
                cefrLevel: (event.target.value ||
                  undefined) as VocabularyQuery['cefrLevel'],
              })
            }
          />
        </Field>
        <Field label="Word type">
          <Select
            value={query.partOfSpeech ?? ''}
            placeholder="Any type"
            options={PARTS_OF_SPEECH.map((partOfSpeech) => ({
              value: partOfSpeech,
              label: PART_OF_SPEECH_LABELS[partOfSpeech],
            }))}
            onChange={(event) =>
              onChange({
                partOfSpeech: (event.target.value ||
                  undefined) as VocabularyQuery['partOfSpeech'],
              })
            }
          />
        </Field>
        {tags && tags.length > 0 && (
          <Field label="Tag">
            <Select
              value={query.tagId ?? ''}
              placeholder="Any tag"
              options={tags.map((tag) => ({
                value: tag.id,
                label: `${tag.name} (${tag.wordCount})`,
              }))}
              onChange={(event) =>
                onChange({ tagId: event.target.value || undefined })
              }
            />
          </Field>
        )}
        <Field label="Sort by">
          <Select
            value={query.sort}
            options={VOCABULARY_SORTS.map((option) => ({ ...option }))}
            onChange={(event) =>
              onChange({
                sort: event.target.value as VocabularyQuery['sort'],
              })
            }
          />
        </Field>
      </div>
      {hasFilters(query) && (
        <Button variant="ghost" size="sm" onClick={onClear}>
          <X className="size-4" aria-hidden="true" />
          Clear filters
        </Button>
      )}
    </div>
  )
}
