import { useState } from 'react'
import { List } from 'lucide-react'
import { Button, ButtonLink, PageSkeleton } from '@/components/ui'
import { REVIEW_GRADES, type ReviewGrade } from '@/reviews/reviewsApi'
import { ReviewCard } from './ReviewCard'
import { ReviewOverview } from './ReviewOverview'
import { cardCount, formatDuration, formatTime } from './reviewText'
import { useReviewSession, type ReviewAnswer } from './useReviewSession'

/** Where the "type the answer" choice is remembered, per browser. */
const TYPING_KEY = 'lf-review-typing'

function readTyping(): boolean {
  try {
    return localStorage.getItem(TYPING_KEY) === 'true'
  } catch {
    return false
  }
}

function saveTyping(typing: boolean) {
  try {
    if (typing) localStorage.setItem(TYPING_KEY, 'true')
    else localStorage.removeItem(TYPING_KEY)
  } catch {
    // Storage may be blocked; the choice just isn't remembered
  }
}

/** /flashcards: daily spaced-repetition review (FE-FC-01). */
export function ReviewPage() {
  const session = useReviewSession()
  const [typing, setTyping] = useState(readTyping)

  const changeTyping = (value: boolean) => {
    setTyping(value)
    saveTyping(value)
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Flashcards</h1>
          <p className="mt-2 text-fg-muted">
            Review your words with spaced repetition.
          </p>
        </div>
        {session.phase !== 'reviewing' && (
          <ButtonLink to="/flashcards/browse" variant="secondary">
            <List className="size-4" aria-hidden="true" />
            All cards
          </ButtonLink>
        )}
      </div>

      {session.phase === 'idle' || session.phase === 'loading' ? (
        <ReviewOverview
          typing={typing}
          onTypingChange={changeTyping}
          onStart={() => void session.start()}
          starting={session.phase === 'loading'}
        />
      ) : session.phase === 'reviewing' && session.current ? (
        <div className="space-y-4">
          <SessionProgress
            done={session.answers.length}
            total={session.total}
            onEnd={session.finish}
          />
          <ReviewCard
            key={session.token}
            item={session.current}
            typing={typing}
            submitting={session.submitting}
            onGrade={(grade) => void session.grade(grade)}
          />
        </div>
      ) : session.phase === 'summary' ? (
        <ReviewSummary
          answers={session.answers}
          elapsedMs={session.elapsedMs}
          nextLearningDueAt={session.nextLearningDueAt}
          onContinue={() => void session.resume()}
          onDone={session.leave}
        />
      ) : (
        <PageSkeleton label="Loading cards" cards={1} />
      )}
    </div>
  )
}

function SessionProgress({
  done,
  total,
  onEnd,
}: {
  done: number
  total: number
  onEnd: () => void
}) {
  const percent = total === 0 ? 0 : Math.round((done / total) * 100)
  return (
    <div className="flex items-center gap-4">
      <div className="flex-1">
        <p className="mb-1.5 text-sm text-fg-muted">
          {done} of {total} reviewed
        </p>
        <div
          role="progressbar"
          aria-label="Review progress"
          aria-valuemin={0}
          aria-valuemax={total}
          aria-valuenow={done}
          className="h-2 rounded-full bg-surface-muted"
        >
          <div
            className="h-2 rounded-full bg-primary transition-[width] motion-reduce:transition-none"
            style={{ width: `${percent}%` }}
          />
        </div>
      </div>
      <Button variant="ghost" size="sm" onClick={onEnd}>
        End session
      </Button>
    </div>
  )
}

const GRADE_LABELS: Record<ReviewGrade, string> = {
  AGAIN: 'Again',
  HARD: 'Hard',
  GOOD: 'Good',
  EASY: 'Easy',
}

function ReviewSummary({
  answers,
  elapsedMs,
  nextLearningDueAt,
  onContinue,
  onDone,
}: {
  answers: ReviewAnswer[]
  elapsedMs: number
  nextLearningDueAt?: string
  onContinue: () => void
  onDone: () => void
}) {
  const remembered = answers.filter((a) => a.grade !== 'AGAIN').length
  const counts = REVIEW_GRADES.map((grade) => ({
    grade,
    count: answers.filter((a) => a.grade === grade).length,
  }))

  return (
    <section
      aria-labelledby="summary-title"
      className="space-y-5 rounded-xl border border-border bg-surface p-6 shadow-sm"
    >
      <div>
        <h2 id="summary-title" className="text-xl font-semibold text-fg">
          {answers.length === 0
            ? 'Nothing to review right now'
            : 'Session done'}
        </h2>
        {answers.length > 0 && (
          <p className="mt-1 text-fg-muted">
            {cardCount(answers.length)} in {formatDuration(elapsedMs)} ·{' '}
            {Math.round((remembered / answers.length) * 100)}% remembered
          </p>
        )}
      </div>

      {answers.length > 0 && (
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {counts.map(({ grade, count }) => (
            <div
              key={grade}
              className="rounded-lg border border-border px-3 py-2 text-center"
            >
              <dt className="text-sm text-fg-muted">{GRADE_LABELS[grade]}</dt>
              <dd className="text-xl font-semibold text-fg tabular-nums">
                {count}
              </dd>
            </div>
          ))}
        </dl>
      )}

      {nextLearningDueAt && (
        <p className="text-fg-muted">
          Cards you're still learning come back at{' '}
          {formatTime(nextLearningDueAt)}.
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        {nextLearningDueAt && (
          <Button onClick={onContinue}>Check for due cards</Button>
        )}
        <Button
          variant={nextLearningDueAt ? 'secondary' : 'primary'}
          onClick={onDone}
        >
          Back to overview
        </Button>
      </div>
    </section>
  )
}
