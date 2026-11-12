import { useMutation } from '@tanstack/react-query'
import { ArrowRight, Sparkles } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Badge, Button } from '@/components/ui'
import {
  submitAttempt,
  type AttemptResult,
  type Exercise,
  type ExerciseAnswer,
} from '@/exercises/exercisesApi'
import { AnswerFeedback } from './AnswerFeedback'
import {
  ChoiceInput,
  FillBlankInput,
  TextInput,
  WordOrderInput,
} from './AnswerInputs'
import { DEFAULT_INSTRUCTIONS, splitGaps, TYPE_LABELS } from './exerciseText'

/** What the learner has entered so far, by input type. */
type Draft =
  | { kind: 'blanks'; blanks: string[] }
  | { kind: 'choice'; index: number | undefined }
  | { kind: 'order'; order: number[] }
  | { kind: 'text'; text: string }

function emptyDraft(exercise: Exercise): Draft {
  switch (exercise.type) {
    case 'FILL_BLANK':
      return {
        kind: 'blanks',
        blanks: splitGaps(exercise.prompt.text)
          .slice(1)
          .map(() => ''),
      }
    case 'MULTIPLE_CHOICE':
      return { kind: 'choice', index: undefined }
    case 'WORD_ORDER':
      return { kind: 'order', order: [] }
    case 'ERROR_CORRECTION':
      // Start from the sentence: fixing it is less typing than rewriting it
      return { kind: 'text', text: exercise.prompt.sentence }
    default:
      return { kind: 'text', text: '' }
  }
}

function answerOf(exercise: Exercise, draft: Draft): ExerciseAnswer | null {
  switch (draft.kind) {
    case 'blanks':
      return draft.blanks.some((blank) => blank.trim())
        ? { blanks: draft.blanks }
        : null
    case 'choice':
      return draft.index === undefined ? null : { index: draft.index }
    case 'order':
      return draft.order.length === 0 || exercise.type !== 'WORD_ORDER'
        ? null
        : { tokens: draft.order.map((i) => exercise.prompt.tokens[i]!) }
    case 'text':
      return draft.text.trim() ? { text: draft.text } : null
  }
}

/**
 * One exercise (FE-EX-01): answer, check, feedback, then Next. Remount it per exercise.
 * In a practice session (FE-PRC-01) the attempt names the session.
 */
export function ExerciseCard({
  exercise,
  last,
  practiceSessionId,
  onAnswered,
  onNext,
}: {
  exercise: Exercise
  last: boolean
  practiceSessionId?: string
  onAnswered: (result: AttemptResult) => void
  onNext: (when: number) => void
}) {
  const [draft, setDraft] = useState<Draft>(() => emptyDraft(exercise))
  const shownAt = useRef(0)
  const nextRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    shownAt.current = Date.now()
  }, [])

  const submit = useMutation({
    mutationFn: (answer: ExerciseAnswer) =>
      submitAttempt(
        exercise.id,
        answer,
        Date.now() - shownAt.current,
        practiceSessionId,
      ),
    onSuccess: onAnswered,
    // The global toast reports a failure (e.g. the AI being busy); the answer stays
  })
  const result = submit.data
  const answer = answerOf(exercise, draft)
  const locked = result !== undefined || submit.isPending

  useEffect(() => {
    if (result) nextRef.current?.focus()
  }, [result])

  return (
    <article aria-label={TYPE_LABELS[exercise.type]} className="space-y-6">
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          if (answer && !locked) submit.mutate(answer)
        }}
        className="space-y-5 rounded-xl border border-border bg-surface p-5 shadow-sm"
      >
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="primary">{TYPE_LABELS[exercise.type]}</Badge>
          {exercise.difficulty && (
            <Badge>{exercise.difficulty.toLowerCase()}</Badge>
          )}
        </div>
        <h1 className="text-base font-medium text-fg-muted">
          {exercise.prompt.instruction ?? DEFAULT_INSTRUCTIONS[exercise.type]}
        </h1>
        <AnswerInput
          exercise={exercise}
          draft={draft}
          setDraft={setDraft}
          result={result}
          locked={locked}
        />
        {!result && (
          <div className="flex justify-end">
            <Button type="submit" disabled={!answer || submit.isPending}>
              {submit.isPending ? (
                <>
                  <Sparkles className="size-4" aria-hidden="true" />
                  Checking…
                </>
              ) : (
                'Check'
              )}
            </Button>
          </div>
        )}
      </form>

      {result && (
        <>
          <AnswerFeedback exercise={exercise} result={result} />
          <div className="flex justify-end">
            <Button ref={nextRef} onClick={() => onNext(Date.now())}>
              {last ? 'See results' : 'Next'}
              <ArrowRight className="size-4" aria-hidden="true" />
            </Button>
          </div>
        </>
      )}
    </article>
  )
}

function AnswerInput({
  exercise,
  draft,
  setDraft,
  result,
  locked,
}: {
  exercise: Exercise
  draft: Draft
  setDraft: (draft: Draft) => void
  result: AttemptResult | undefined
  locked: boolean
}) {
  switch (exercise.type) {
    case 'FILL_BLANK':
      return (
        <FillBlankInput
          prompt={exercise.prompt}
          value={draft.kind === 'blanks' ? draft.blanks : []}
          onChange={(blanks) => setDraft({ kind: 'blanks', blanks })}
          disabled={locked}
          parts={result?.parts}
        />
      )
    case 'MULTIPLE_CHOICE':
      return (
        <ChoiceInput
          prompt={exercise.prompt}
          value={draft.kind === 'choice' ? draft.index : undefined}
          onChange={(index) => setDraft({ kind: 'choice', index })}
          disabled={locked}
          correctAnswer={result?.correctAnswer}
        />
      )
    case 'WORD_ORDER':
      return (
        <WordOrderInput
          prompt={exercise.prompt}
          value={draft.kind === 'order' ? draft.order : []}
          onChange={(order) => setDraft({ kind: 'order', order })}
          disabled={locked}
          parts={result?.parts}
        />
      )
    default:
      return (
        <TextInput
          exercise={exercise}
          value={draft.kind === 'text' ? draft.text : ''}
          onChange={(text) => setDraft({ kind: 'text', text })}
          disabled={locked}
        />
      )
  }
}
