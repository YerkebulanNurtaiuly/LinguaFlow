import { useState } from 'react'
import { describeError } from '@/api/errors'
import {
  Badge,
  Button,
  LoadError,
  Modal,
  Skeleton,
  Table,
  TableCaption,
  TBody,
  Td,
  Th,
  THead,
  Tr,
  useToast,
} from '@/components/ui'
import type { Flashcard } from '@/flashcards/flashcardsApi'
import { useCardReviews, useSetSuspended } from '@/flashcards/queries'
import type { ReviewGrade } from '@/reviews/reviewsApi'
import { DIRECTION_LABELS, formatDue, STATE_LABELS } from './browserFilters'
import { GermanText } from './ReviewCard'

const GRADE_BADGES: Record<
  ReviewGrade,
  { label: string; variant: 'danger' | 'warning' | 'success' | 'info' }
> = {
  AGAIN: { label: 'Again', variant: 'danger' },
  HARD: { label: 'Hard', variant: 'warning' },
  GOOD: { label: 'Good', variant: 'success' },
  EASY: { label: 'Easy', variant: 'info' },
}

const dateTime = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

const days = (n: number) => (n === 1 ? '1 day' : `${n} days`)

/** One card's content, review state and history (FE-FC-02), with suspending. */
export function CardDetailsModal({
  card,
  onClose,
}: {
  card: Flashcard
  onClose: () => void
}) {
  const setSuspended = useSetSuspended()
  const { toast } = useToast()
  // The card as the last change returned it, so the dialog shows the new state at once
  const [current, setCurrent] = useState(card)
  const [now] = useState(() => Date.now())
  const germanFront = current.direction === 'TARGET_TO_NATIVE'

  const toggle = async () => {
    try {
      const updated = await setSuspended.mutateAsync({
        id: current.id,
        suspended: !current.suspended,
      })
      setCurrent(updated)
      toast({
        title: updated.suspended
          ? 'Card suspended: it stays out of reviews'
          : 'Card back in reviews',
        variant: 'success',
      })
    } catch {
      // The global toast reports the failure
    }
  }

  const { srs } = current
  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title={current.lemma}
      description={DIRECTION_LABELS[current.direction]}
      footer={
        <>
          <Button
            variant="secondary"
            onClick={() => void toggle()}
            disabled={setSuspended.isPending}
          >
            {current.suspended ? 'Unsuspend' : 'Suspend'}
          </Button>
          <Button onClick={onClose}>Close</Button>
        </>
      }
    >
      <div className="space-y-5">
        <div className="grid gap-3 rounded-lg border border-border p-4 sm:grid-cols-2">
          <div>
            <p className="text-xs font-medium tracking-wide text-fg-subtle uppercase">
              Front
            </p>
            <p className="mt-1 text-lg font-semibold text-fg">
              {germanFront ? (
                <GermanText text={current.content.front} />
              ) : (
                current.content.front
              )}
              {current.content.hint && (
                <span className="ml-2 text-sm font-normal text-fg-muted">
                  ({current.content.hint})
                </span>
              )}
            </p>
          </div>
          <div>
            <p className="text-xs font-medium tracking-wide text-fg-subtle uppercase">
              Back
            </p>
            <p className="mt-1 text-lg font-semibold text-fg">
              {germanFront ? (
                current.content.back
              ) : (
                <GermanText text={current.content.back} />
              )}
            </p>
          </div>
        </div>

        <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-3">
          <Detail label="State">
            {STATE_LABELS[srs.state]}
            {current.suspended && (
              <Badge variant="warning" className="ml-2">
                Suspended
              </Badge>
            )}
          </Detail>
          <Detail label="Due">{formatDue(srs.dueAt, srs.state, now)}</Detail>
          <Detail label="Interval">{days(srs.intervalDays)}</Detail>
          <Detail label="Successful in a row">{srs.repetitions}</Detail>
          <Detail label="Lapses">{srs.lapses}</Detail>
          <Detail label="Last reviewed">
            {srs.lastReviewedAt
              ? dateTime.format(new Date(srs.lastReviewedAt))
              : 'Never'}
          </Detail>
        </dl>

        <ReviewHistory cardId={current.id} />
      </div>
    </Modal>
  )
}

function Detail({
  label,
  children,
}: {
  label: string
  children: React.ReactNode
}) {
  return (
    <div>
      <dt className="text-fg-subtle">{label}</dt>
      <dd className="mt-0.5 font-medium text-fg">{children}</dd>
    </div>
  )
}

function ReviewHistory({ cardId }: { cardId: string }) {
  const reviews = useCardReviews(cardId)

  return (
    <section aria-labelledby="history-title" className="space-y-2">
      <h3 id="history-title" className="text-sm font-semibold text-fg">
        Review history
      </h3>
      {reviews.isError && !reviews.data ? (
        <LoadError
          title="Couldn't load the history"
          description={describeError(reviews.error)}
          onRetry={() => void reviews.refetch()}
          retrying={reviews.isFetching}
        />
      ) : !reviews.data ? (
        <Skeleton className="h-16" />
      ) : reviews.data.totalElements === 0 ? (
        <p className="text-sm text-fg-muted">Not reviewed yet.</p>
      ) : (
        <Table>
          <TableCaption>
            {reviews.data.totalElements === 1
              ? '1 review'
              : `${reviews.data.totalElements} reviews`}
            , newest first
          </TableCaption>
          <THead>
            <Tr>
              <Th>When</Th>
              <Th>Grade</Th>
              <Th>Interval (days)</Th>
              <Th className="hidden sm:table-cell">Time</Th>
            </Tr>
          </THead>
          <TBody>
            {reviews.data.content.map((review) => (
              <Tr key={review.id}>
                <Td className="text-fg-muted">
                  {dateTime.format(new Date(review.reviewedAt))}
                </Td>
                <Td>
                  <Badge variant={GRADE_BADGES[review.grade].variant}>
                    {GRADE_BADGES[review.grade].label}
                  </Badge>
                </Td>
                <Td className="text-fg-muted">
                  {review.intervalBefore} → {review.intervalAfter}
                </Td>
                <Td className="hidden text-fg-muted sm:table-cell">
                  {review.durationMs === undefined
                    ? '—'
                    : `${Math.round(review.durationMs / 1000)} s`}
                </Td>
              </Tr>
            ))}
          </TBody>
        </Table>
      )}
    </section>
  )
}
