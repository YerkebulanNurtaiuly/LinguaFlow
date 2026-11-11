import {
  ArrowLeft,
  ChevronLeft,
  ChevronRight,
  Layers,
  Search,
  SearchX,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { describeError } from '@/api/errors'
import {
  Badge,
  Button,
  ButtonLink,
  EmptyState,
  Field,
  Input,
  LoadError,
  PageSkeleton,
  Select,
  Table,
  TableCaption,
  TBody,
  Td,
  Th,
  THead,
  Tr,
} from '@/components/ui'
import {
  CARD_DIRECTIONS,
  PAGE_SIZE,
  type Flashcard,
  type FlashcardQuery,
} from '@/flashcards/flashcardsApi'
import { useFlashcardList } from '@/flashcards/queries'
import { cn } from '@/lib/cn'
import { SRS_STATES } from '@/reviews/reviewsApi'
import {
  DIRECTION_LABELS,
  formatDue,
  hasFilters,
  paramsFromQuery,
  queryFromParams,
  STATE_LABELS,
} from './browserFilters'
import { CardDetailsModal } from './CardDetailsModal'
import { GermanText } from './ReviewCard'

/** How long typing pauses before the search runs. */
const SEARCH_DELAY_MS = 300

const cardCount = (n: number) =>
  `${n.toLocaleString()} ${n === 1 ? 'card' : 'cards'}`

/**
 * /flashcards/browse: every card of the active language, searchable and filterable,
 * with each card's details, suspension and review history (FE-FC-02).
 */
export function FlashcardBrowserPage() {
  const [params, setParams] = useSearchParams()
  const query = useMemo(() => queryFromParams(params), [params])
  const list = useFlashcardList(query)
  const [open, setOpen] = useState<Flashcard | null>(null)
  const [now] = useState(() => Date.now())

  const update = useCallback(
    (changes: Partial<FlashcardQuery>) => {
      setParams(
        (current) => {
          const next = { ...queryFromParams(current), ...changes }
          if (!('page' in changes)) next.page = 0
          return paramsFromQuery(next)
        },
        { replace: !('page' in changes) },
      )
    },
    [setParams],
  )
  const clearFilters = () =>
    setParams(paramsFromQuery({ sort: query.sort, page: 0 }))

  const data = list.data
  const filtered = hasFilters(query)
  const first = data ? data.page * PAGE_SIZE + 1 : 0
  const last = data ? first + data.content.length - 1 : 0

  return (
    <div className="space-y-6">
      <div>
        <ButtonLink
          to="/flashcards"
          variant="ghost"
          size="sm"
          className="-ml-3"
        >
          <ArrowLeft className="size-4" aria-hidden="true" />
          Review
        </ButtonLink>
        <h1 className="mt-2 text-2xl font-semibold tracking-tight">
          All flashcards
        </h1>
        <p className="mt-2 text-fg-muted">
          {data && !filtered
            ? `${cardCount(data.totalElements)}, two for each word.`
            : 'Search your cards, see their history, and leave some out of reviews.'}
        </p>
      </div>

      <FilterBar query={query} onChange={update} onClear={clearFilters} />

      {list.isError && !data ? (
        <LoadError
          title="Couldn't load your flashcards"
          description={describeError(list.error)}
          onRetry={() => void list.refetch()}
          retrying={list.isFetching}
        />
      ) : !data ? (
        <PageSkeleton label="Loading flashcards" cards={1} withHeader={false} />
      ) : data.totalElements === 0 ? (
        filtered ? (
          <EmptyState
            icon={SearchX}
            title="No cards match"
            description="Try another search or fewer filters."
            action={
              <Button variant="secondary" onClick={clearFilters}>
                Clear filters
              </Button>
            }
          />
        ) : (
          <EmptyState
            icon={Layers}
            title="No flashcards yet"
            description="Each word gets two cards once its details are filled in."
            action={<ButtonLink to="/vocabulary">Go to vocabulary</ButtonLink>}
          />
        )
      ) : (
        <div
          className={cn(
            'space-y-4 transition-opacity',
            list.isPlaceholderData && 'opacity-60',
          )}
          aria-busy={list.isPlaceholderData}
        >
          <Table>
            <TableCaption>{`Your flashcards, ${first} to ${last} of ${data.totalElements}`}</TableCaption>
            <THead>
              <Tr>
                <Th>Card</Th>
                <Th className="hidden md:table-cell">Direction</Th>
                <Th className="hidden sm:table-cell">State</Th>
                <Th>Due</Th>
              </Tr>
            </THead>
            <TBody>
              {data.content.map((card) => (
                <CardRow
                  key={card.id}
                  card={card}
                  now={now}
                  onOpen={() => setOpen(card)}
                />
              ))}
            </TBody>
          </Table>
          <nav
            aria-label="Pagination"
            className="flex flex-wrap items-center justify-between gap-3 text-sm text-fg-muted"
          >
            <p aria-live="polite">
              {first}–{last} of {cardCount(data.totalElements)}
            </p>
            {data.totalPages > 1 && (
              <div className="flex items-center gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={query.page === 0}
                  onClick={() => update({ page: query.page - 1 })}
                >
                  <ChevronLeft className="size-4" aria-hidden="true" />
                  Previous
                </Button>
                <span>
                  Page {data.page + 1} of {data.totalPages}
                </span>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={data.page + 1 >= data.totalPages}
                  onClick={() => update({ page: query.page + 1 })}
                >
                  Next
                  <ChevronRight className="size-4" aria-hidden="true" />
                </Button>
              </div>
            )}
          </nav>
        </div>
      )}

      {open && (
        <CardDetailsModal
          key={open.id}
          card={open}
          onClose={() => setOpen(null)}
        />
      )}
    </div>
  )
}

function CardRow({
  card,
  now,
  onOpen,
}: {
  card: Flashcard
  now: number
  onOpen: () => void
}) {
  const germanFront = card.direction === 'TARGET_TO_NATIVE'
  const { content, srs } = card
  return (
    <Tr className={card.suspended ? 'opacity-60' : undefined}>
      <Td>
        <button
          type="button"
          onClick={onOpen}
          aria-label={`${content.front} → ${content.back}, ${DIRECTION_LABELS[card.direction]}`}
          className="rounded text-left text-fg hover:text-primary hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        >
          <span className="font-medium">
            {germanFront ? <GermanText text={content.front} /> : content.front}
          </span>
          <span className="text-fg-muted">
            {' → '}
            {germanFront ? content.back : <GermanText text={content.back} />}
          </span>
        </button>
        <div className="mt-1 flex flex-wrap gap-1">
          {card.suspended && <Badge variant="warning">Suspended</Badge>}
          {!content.complete && <Badge>No translation yet</Badge>}
          <span className="sm:hidden">
            <Badge>{STATE_LABELS[srs.state]}</Badge>
          </span>
        </div>
      </Td>
      <Td className="hidden text-fg-muted md:table-cell">
        {DIRECTION_LABELS[card.direction]}
      </Td>
      <Td className="hidden sm:table-cell">
        <Badge variant={srs.state === 'NEW' ? 'neutral' : 'primary'}>
          {STATE_LABELS[srs.state]}
        </Badge>
      </Td>
      <Td className="text-fg-muted">{formatDue(srs.dueAt, srs.state, now)}</Td>
    </Tr>
  )
}

function FilterBar({
  query,
  onChange,
  onClear,
}: {
  query: FlashcardQuery
  onChange: (changes: Partial<FlashcardQuery>) => void
  onClear: () => void
}) {
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
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div className="relative sm:col-span-2 lg:col-span-4">
          <Field label="Search">
            <Input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Word or translation, e.g. Hund"
              className="pl-9"
              maxLength={200}
            />
          </Field>
          <Search
            className="pointer-events-none absolute bottom-3 left-3 size-4 text-fg-subtle"
            aria-hidden="true"
          />
        </div>
        <Field label="Direction">
          <Select
            value={query.direction ?? ''}
            onChange={(event) =>
              onChange({
                direction: CARD_DIRECTIONS.find(
                  (d) => d === event.target.value,
                ),
              })
            }
            placeholder="Both"
            options={CARD_DIRECTIONS.map((d) => ({
              value: d,
              label: DIRECTION_LABELS[d],
            }))}
          />
        </Field>
        <Field label="State">
          <Select
            value={query.state ?? ''}
            onChange={(event) =>
              onChange({
                state: SRS_STATES.find((s) => s === event.target.value),
              })
            }
            placeholder="Any"
            options={SRS_STATES.map((s) => ({
              value: s,
              label: STATE_LABELS[s],
            }))}
          />
        </Field>
        <Field label="Suspended">
          <Select
            value={
              query.suspended === undefined
                ? ''
                : query.suspended
                  ? 'yes'
                  : 'no'
            }
            onChange={(event) =>
              onChange({
                suspended:
                  event.target.value === ''
                    ? undefined
                    : event.target.value === 'yes',
              })
            }
            placeholder="All cards"
            options={[
              { value: 'no', label: 'In reviews' },
              { value: 'yes', label: 'Suspended' },
            ]}
          />
        </Field>
        <Field label="Sort">
          <Select
            value={query.sort}
            onChange={(event) =>
              onChange({
                sort:
                  event.target.value === 'dueAt,asc'
                    ? 'dueAt,asc'
                    : 'createdAt,desc',
              })
            }
            options={[
              { value: 'createdAt,desc', label: 'Newest first' },
              { value: 'dueAt,asc', label: 'Due soonest' },
            ]}
          />
        </Field>
      </div>
      {hasFilters(query) && (
        <Button variant="ghost" size="sm" onClick={onClear}>
          Clear filters
        </Button>
      )}
    </div>
  )
}
