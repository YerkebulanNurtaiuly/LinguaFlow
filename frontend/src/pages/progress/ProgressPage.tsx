import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Clock, Flame, ListChecks, Target } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { describeError } from '@/api/errors'
import {
  Button,
  ButtonLink,
  Card,
  CardContent,
  CardSkeleton,
  Field,
  LoadError,
  Select,
  Tabs,
} from '@/components/ui'
import { EXERCISES_QUERY_KEY } from '@/exercises/queries'
import { reasonText } from '@/pages/dashboard/dashboardText'
import { plural } from '@/pages/practice/practiceText'
import {
  startWeakSpots,
  type MistakeGroup,
  type MistakeGrouping,
  type ProgressStats,
  type WeakPoint,
} from '@/progress/progressApi'
import {
  useFrequentMistakes,
  useProgressStats,
  useWeakPoints,
} from '@/progress/queries'
import { AccuracyChart } from './charts/AccuracyChart'
import { ActivityHeatmap } from './charts/ActivityHeatmap'
import { percent, shortDate } from './charts/chartMath'
import { MasteryBar, StatusLegend } from './charts/MasteryBar'
import { STATUS_STEPS, type Status } from './charts/statusSteps'
import { formatMinutes, humanizeCode } from './progressText'

const PERIODS = [
  { value: '30', label: 'Last 30 days' },
  { value: '90', label: 'Last 90 days' },
  { value: '182', label: 'Last 6 months' },
  { value: '365', label: 'Last year' },
]

/**
 * /progress (FE-PRG-02): one period filter scoping the figures, activity heatmap,
 * accuracy trend and frequent mistakes; the mastery breakdown and weak points are as
 * of now. Weak points have "practise now" buttons.
 */
export function ProgressPage() {
  const [days, setDays] = useState(90)
  const stats = useProgressStats(days)
  return (
    <div className="max-w-5xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Progress</h1>
        <p className="mt-2 text-fg-muted">
          How your practice is going, and what to work on next.
        </p>
      </div>
      <div className="max-w-xs">
        <Field label="Period">
          <Select
            value={String(days)}
            onChange={(event) => setDays(Number(event.target.value))}
            options={PERIODS}
          />
        </Field>
      </div>
      {stats.isError && !stats.data ? (
        <LoadError
          title="Couldn't load your progress"
          description={describeError(stats.error)}
          onRetry={() => void stats.refetch()}
          retrying={stats.isFetching}
        />
      ) : !stats.data ? (
        <div className="space-y-4">
          <CardSkeleton lines={2} />
          <CardSkeleton lines={4} />
        </div>
      ) : (
        // Refetching keeps the frame: the old figures stay, dimmed
        <div
          className={`space-y-6 transition-opacity ${stats.isPlaceholderData ? 'opacity-60' : ''}`}
        >
          <Figures stats={stats.data} />
          <ChartCard
            title="Activity"
            subtitle="Flashcard reviews and exercises per day"
          >
            <ActivityHeatmap days={stats.data.activity} />
            <ActivityTable days={stats.data.activity} />
          </ChartCard>
          <ChartCard
            title="Accuracy over time"
            subtitle="Per week: flashcards you remembered, and your average exercise score"
          >
            <AccuracyChart weeks={stats.data.accuracy} />
          </ChartCard>
          <ChartCard
            title="Mastery"
            subtitle="Your words and grammar topics by status, as of today"
          >
            <Mastery stats={stats.data} />
          </ChartCard>
        </div>
      )}
      <WeakPoints />
      <Mistakes days={days} />
    </div>
  )
}

function ChartCard({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle: string
  children: React.ReactNode
}) {
  return (
    <Card>
      <CardContent className="space-y-3">
        <div>
          <h2 className="text-lg font-semibold">{title}</h2>
          <p className="text-sm text-fg-muted">{subtitle}</p>
        </div>
        {children}
      </CardContent>
    </Card>
  )
}

function Figures({ stats }: { stats: ProgressStats }) {
  const answers = stats.activity.reduce(
    (sum, day) => sum + day.reviews + day.exercises,
    0,
  )
  const activeDays = stats.activity.filter(
    (day) => day.reviews + day.exercises > 0,
  ).length
  const tiles = [
    {
      icon: Clock,
      label: 'Time studied',
      value: formatMinutes(stats.timeStudied.periodMs),
      note: `${formatMinutes(stats.timeStudied.todayMs)} today`,
    },
    {
      icon: ListChecks,
      label: 'Answers',
      value: answers.toLocaleString(),
      note: `on ${plural(activeDays, 'day')}`,
    },
    {
      icon: Flame,
      label: 'Streak',
      value: plural(stats.currentStreak, 'day'),
      note: `Longest: ${plural(stats.longestStreak, 'day')}`,
    },
  ]
  return (
    <section aria-label="Figures" className="grid gap-4 sm:grid-cols-3">
      {tiles.map((tile) => (
        <Card key={tile.label}>
          <CardContent>
            <p className="flex items-center gap-2 text-sm text-fg-muted">
              <tile.icon className="size-4" aria-hidden="true" />
              {tile.label}
            </p>
            <p className="mt-2 text-2xl font-semibold text-fg">{tile.value}</p>
            <p className="mt-1 text-sm text-fg-subtle">{tile.note}</p>
          </CardContent>
        </Card>
      ))}
    </section>
  )
}

/** The heatmap's numbers, for reading without the grid (and with a screen reader). */
function ActivityTable({ days }: { days: ProgressStats['activity'] }) {
  const active = days.filter((day) => day.reviews + day.exercises > 0)
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-primary">
        Show the days as a table
      </summary>
      {active.length === 0 ? (
        <p className="mt-2 text-fg-muted">No practice in this period.</p>
      ) : (
        <table className="mt-2 w-full text-left">
          <thead className="text-fg-muted">
            <tr>
              <th className="py-1 font-medium">Day</th>
              <th className="py-1 text-right font-medium">Reviews</th>
              <th className="py-1 text-right font-medium">Exercises</th>
              <th className="py-1 text-right font-medium">Minutes</th>
            </tr>
          </thead>
          <tbody className="tabular-nums">
            {[...active].reverse().map((day) => (
              <tr key={day.date} className="border-t border-border">
                <td className="py-1">{shortDate(day.date)}</td>
                <td className="py-1 text-right">{day.reviews}</td>
                <td className="py-1 text-right">{day.exercises}</td>
                <td className="py-1 text-right">
                  {Math.round(day.studiedMs / 60_000)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </details>
  )
}

function countsOf(material: ProgressStats['words']): Record<Status, number> {
  const counts: Record<Status, number> = {
    NEW: 0,
    LEARNING: 0,
    LEARNED: 0,
    MASTERED: 0,
  }
  material.byStatus.forEach((entry) => {
    counts[entry.status] = entry.count
  })
  return counts
}

function Mastery({ stats }: { stats: ProgressStats }) {
  const words = countsOf(stats.words)
  const topics = countsOf(stats.grammarTopics)
  return (
    <div className="space-y-4">
      <StatusLegend />
      <MasteryBar name="Words" counts={words} />
      <MasteryBar name="Grammar topics" counts={topics} />
      <details className="text-sm">
        <summary className="cursor-pointer text-primary">
          Show the numbers
        </summary>
        <table className="mt-2 w-full text-left">
          <thead className="text-fg-muted">
            <tr>
              <th className="py-1 font-medium">Status</th>
              <th className="py-1 text-right font-medium">Words</th>
              <th className="py-1 text-right font-medium">Grammar topics</th>
            </tr>
          </thead>
          <tbody className="tabular-nums">
            {STATUS_STEPS.map((step) => (
              <tr key={step.status} className="border-t border-border">
                <td className="py-1">{step.label}</td>
                <td className="py-1 text-right">{words[step.status]}</td>
                <td className="py-1 text-right">{topics[step.status]}</td>
              </tr>
            ))}
            <tr className="border-t border-border font-medium">
              <td className="py-1">Total</td>
              <td className="py-1 text-right">{stats.words.total}</td>
              <td className="py-1 text-right">{stats.grammarTopics.total}</td>
            </tr>
          </tbody>
        </table>
      </details>
    </div>
  )
}

function WeakPoints() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const weak = useWeakPoints(8)
  const start = useMutation({
    mutationFn: startWeakSpots,
    meta: { errorTitle: "Couldn't start practising" },
    onSuccess: async (started) => {
      await queryClient.invalidateQueries({ queryKey: EXERCISES_QUERY_KEY })
      void navigate(`/practice/exercises/${started.exerciseSetId}`)
    },
  })
  return (
    <section aria-labelledby="weak-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="weak-heading" className="text-lg font-semibold">
            Weak points
          </h2>
          <p className="text-sm text-fg-muted">
            What you get wrong most, or haven’t practised for a while
          </p>
        </div>
        <Button
          disabled={start.isPending || weak.data?.grammarTopics.length === 0}
          onClick={() => start.mutate()}
        >
          <Target className="size-4" aria-hidden="true" />
          {start.isPending ? 'Starting…' : 'Practise my weak spots'}
        </Button>
      </div>
      {weak.isError && !weak.data ? (
        <LoadError
          title="Couldn't load your weak points"
          description={describeError(weak.error)}
          onRetry={() => void weak.refetch()}
          retrying={weak.isFetching}
        />
      ) : !weak.data ? (
        <CardSkeleton lines={3} />
      ) : weak.data.grammarTopics.length === 0 &&
        weak.data.words.length === 0 ? (
        <p className="rounded-xl border border-border bg-surface p-4 text-fg-muted">
          Nothing weak yet. Your weak points show up here once you have
          practised.
        </p>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          <WeakList
            title="Grammar topics"
            points={weak.data.grammarTopics}
            action={(point) => (
              <ButtonLink
                to={`/practice/exercises?topic=${point.id}`}
                variant="secondary"
                size="sm"
                aria-label={`Practise ${point.name}`}
              >
                Practise
              </ButtonLink>
            )}
          />
          <WeakList title="Words" points={weak.data.words} />
        </div>
      )}
    </section>
  )
}

function WeakList({
  title,
  points,
  action,
}: {
  title: string
  points: WeakPoint[]
  action?: (point: WeakPoint) => React.ReactNode
}) {
  return (
    <Card>
      <CardContent>
        <h3 className="font-semibold">{title}</h3>
        {points.length === 0 ? (
          <p className="mt-2 text-sm text-fg-muted">None weak right now.</p>
        ) : (
          <ul
            aria-label={`Weak ${title.toLowerCase()}`}
            className="mt-2 divide-y divide-border"
          >
            {points.map((point) => (
              <li key={point.id} className="flex items-center gap-3 py-2">
                <div className="min-w-0 flex-1">
                  <p className="font-medium text-fg">
                    {point.name}
                    {point.translation && (
                      <span className="font-normal text-fg-muted">
                        {' '}
                        · {point.translation}
                      </span>
                    )}
                  </p>
                  <p className="text-sm text-fg-muted">
                    {percent(point.score)} weak
                    {point.reasons.length > 0 &&
                      ` · ${point.reasons.map(reasonText).join(' · ')}`}
                  </p>
                </div>
                {action?.(point)}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

function Mistakes({ days }: { days: number }) {
  const [groupBy, setGroupBy] = useState<MistakeGrouping>('TYPE')
  const mistakes = useFrequentMistakes(groupBy, days)
  const list =
    mistakes.isError && !mistakes.data ? (
      <LoadError
        title="Couldn't load your mistakes"
        description={describeError(mistakes.error)}
        onRetry={() => void mistakes.refetch()}
        retrying={mistakes.isFetching}
      />
    ) : !mistakes.data ? (
      <CardSkeleton lines={3} />
    ) : mistakes.data.groups.length === 0 ? (
      <p className="rounded-xl border border-border bg-surface p-4 text-fg-muted">
        No mistakes in this period. Nice!
      </p>
    ) : (
      <ul
        aria-label="Mistake groups"
        className={`space-y-3 transition-opacity ${mistakes.isPlaceholderData ? 'opacity-60' : ''}`}
      >
        {mistakes.data.groups.map((group) => (
          <MistakeCard
            key={group.errorType ?? group.grammarTopic?.id ?? 'none'}
            group={group}
            max={mistakes.data.groups[0]!.count}
          />
        ))}
      </ul>
    )
  return (
    <section aria-labelledby="mistakes-heading" className="space-y-3">
      <div>
        <h2 id="mistakes-heading" className="text-lg font-semibold">
          Frequent mistakes
        </h2>
        <p className="text-sm text-fg-muted">
          From your exercises in the chosen period, with your own answers
        </p>
      </div>
      <Tabs
        label="Group mistakes"
        value={groupBy}
        onChange={(value) => setGroupBy(value as MistakeGrouping)}
        items={[
          { id: 'TYPE', label: 'By mistake', content: list },
          { id: 'TOPIC', label: 'By topic', content: list },
        ]}
      />
    </section>
  )
}

function MistakeCard({ group, max }: { group: MistakeGroup; max: number }) {
  const title = group.errorType
    ? humanizeCode(group.errorType)
    : (group.grammarTopic?.title ?? 'Without a topic')
  const breakdown = group.breakdown
    .map(
      (slice) =>
        `${slice.errorType ? humanizeCode(slice.errorType) : (slice.grammarTopic?.title ?? 'no topic')} ${slice.count}`,
    )
    .join(' · ')
  return (
    <li>
      <Card>
        <CardContent className="space-y-3">
          <div className="flex items-baseline justify-between gap-3">
            <h3 className="font-semibold">{title}</h3>
            <span className="text-sm text-fg-muted">
              {plural(group.count, 'time')}
            </span>
          </div>
          {/* A meter list: one hue, each group against the most frequent */}
          <div className="h-2 rounded-full bg-surface-muted" aria-hidden="true">
            <div
              className="h-full rounded-full bg-viz-3"
              style={{ width: `${(group.count / max) * 100}%` }}
            />
          </div>
          <p className="text-sm text-fg-muted">{breakdown}</p>
          {group.examples.length > 0 && (
            <ul className="space-y-2">
              {group.examples.map((example) => (
                <li
                  key={example.mistakeId}
                  className="rounded-lg bg-surface-muted px-3 py-2 text-sm"
                >
                  {example.attempt ? (
                    <>
                      <p className="text-fg-muted">
                        {example.attempt.exercise}
                      </p>
                      <p className="text-fg">
                        <span className="text-fg-muted">You: </span>
                        {example.attempt.answer}
                      </p>
                      <p className="text-fg">
                        <span className="text-fg-muted">Right: </span>
                        {example.attempt.correctAnswer}
                      </p>
                    </>
                  ) : (
                    <p className="text-fg">{example.excerpt}</p>
                  )}
                  {example.explanation && (
                    <p className="mt-1 text-fg-muted">{example.explanation}</p>
                  )}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </li>
  )
}
