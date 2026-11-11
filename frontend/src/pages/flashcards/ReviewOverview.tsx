import { CheckCircle2, Flame, Layers, Target } from 'lucide-react'
import { describeError } from '@/api/errors'
import {
  Button,
  Card,
  CardContent,
  EmptyState,
  LoadError,
  PageSkeleton,
} from '@/components/ui'
import { useReviewStats } from '@/reviews/queries'
import type { ReviewStats } from '@/reviews/reviewsApi'
import { cardCount, dayCount, formatWeekday } from './reviewText'

export interface ReviewOverviewProps {
  typing: boolean
  onTypingChange: (typing: boolean) => void
  onStart: () => void
  starting: boolean
}

/** /flashcards before a session: what's due, streak, retention and the week ahead. */
export function ReviewOverview({
  typing,
  onTypingChange,
  onStart,
  starting,
}: ReviewOverviewProps) {
  const stats = useReviewStats()

  if (stats.isError && !stats.data) {
    return (
      <LoadError
        title="Couldn't load your reviews"
        description={describeError(stats.error)}
        onRetry={() => void stats.refetch()}
        retrying={stats.isFetching}
      />
    )
  }
  if (!stats.data) return <PageSkeleton label="Loading reviews" cards={3} />

  const data = stats.data
  const toReview = data.dueToday + data.newAvailable

  return (
    <div className="space-y-6">
      {toReview === 0 ? (
        <EmptyState
          icon={CheckCircle2}
          title="All caught up"
          description={
            nextDay(data)
              ? `Nothing is due right now. Next: ${cardCount(nextDay(data)!.due)} on ${formatWeekday(nextDay(data)!.date)}.`
              : 'Nothing is due. Add words to get new cards.'
          }
        />
      ) : (
        <Card>
          <CardContent className="flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <p className="text-3xl font-semibold text-fg">
                {cardCount(toReview)}
              </p>
              <p className="mt-1 text-fg-muted">
                {data.dueToday} due today · {data.newAvailable} new
              </p>
            </div>
            <div className="flex flex-col gap-3 sm:items-end">
              <label className="flex items-center gap-2 text-sm text-fg">
                <input
                  type="checkbox"
                  className="size-4 accent-primary"
                  checked={typing}
                  onChange={(event) => onTypingChange(event.target.checked)}
                />
                Type the answer
              </label>
              <Button size="lg" onClick={onStart} disabled={starting}>
                {starting ? 'Loading…' : 'Start review'}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      <div className="grid gap-4 sm:grid-cols-3">
        <StatTile
          icon={Target}
          label="Reviewed today"
          value={`${data.reviewedToday} / ${data.dailyReviewGoal}`}
          note={
            data.reviewedToday >= data.dailyReviewGoal
              ? 'Daily goal reached'
              : `${data.dailyReviewGoal - data.reviewedToday} to your daily goal`
          }
        />
        <StatTile
          icon={Flame}
          label="Streak"
          value={dayCount(data.currentStreak)}
          note={`Longest: ${dayCount(data.longestStreak)}`}
        />
        <StatTile
          icon={Layers}
          label="Retention"
          value={
            data.retention.rate === undefined
              ? '—'
              : `${Math.round(data.retention.rate * 100)}%`
          }
          note={
            data.retention.reviews === 0
              ? `No reviews of learned cards in ${data.retention.days} days`
              : `${data.retention.remembered} of ${data.retention.reviews} remembered, last ${data.retention.days} days`
          }
        />
      </div>

      <Forecast forecast={data.forecast} />

      <p className="text-sm text-fg-subtle">
        Keys: Space shows the answer, 1–4 grade it (Again, Hard, Good, Easy).
      </p>
    </div>
  )
}

function nextDay(stats: ReviewStats) {
  return stats.forecast.slice(1).find((day) => day.due > 0)
}

function StatTile({
  icon: Icon,
  label,
  value,
  note,
}: {
  icon: typeof Target
  label: string
  value: string
  note: string
}) {
  return (
    <Card>
      <CardContent>
        <p className="flex items-center gap-2 text-sm text-fg-muted">
          <Icon className="size-4" aria-hidden="true" />
          {label}
        </p>
        <p className="mt-2 text-2xl font-semibold text-fg">{value}</p>
        <p className="mt-1 text-sm text-fg-subtle">{note}</p>
      </CardContent>
    </Card>
  )
}

/**
 * The next 7 days as thin bars, one hue, each labelled with its count; a list
 * underneath, so it reads without the bars.
 */
function Forecast({ forecast }: { forecast: ReviewStats['forecast'] }) {
  const max = Math.max(1, ...forecast.map((day) => day.due))
  return (
    <Card>
      <CardContent>
        <h2 className="text-base font-semibold text-fg">Coming up</h2>
        <p className="text-sm text-fg-muted">
          Cards falling due each day; today includes overdue cards.
        </p>
        <ul aria-label="Cards due by day" className="mt-4 space-y-2">
          {forecast.map((day, index) => (
            <li
              key={day.date}
              className="grid grid-cols-[4.5rem_1fr_2.5rem] items-center gap-3 text-sm"
            >
              <span className="text-fg-muted">
                {index === 0 ? 'Today' : formatWeekday(day.date)}
              </span>
              <span className="h-2 rounded-full bg-surface-muted">
                <span
                  className="block h-2 rounded-full bg-primary"
                  style={{ width: `${(day.due / max) * 100}%` }}
                />
              </span>
              <span className="text-right text-fg tabular-nums">
                {day.due}
                <span className="sr-only"> cards</span>
              </span>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}
