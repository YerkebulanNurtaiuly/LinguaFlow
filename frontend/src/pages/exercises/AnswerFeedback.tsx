import { useMutation, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, CircleX, Flag, Lightbulb } from 'lucide-react'
import { useRef, useState } from 'react'
import {
  Alert,
  Button,
  Field,
  Modal,
  Textarea,
  useToast,
} from '@/components/ui'
import {
  explainAttempt,
  reportExercise,
  withdrawReport,
  type AttemptResult,
  type Exercise,
} from '@/exercises/exercisesApi'
import { EXERCISES_QUERY_KEY } from '@/exercises/queries'
import { cn } from '@/lib/cn'
import { GermanText } from '@/pages/flashcards/ReviewCard'
import { errorLabel, formatScore, NOTE_LABELS } from './exerciseText'

/**
 * What the learner sees after answering: right or wrong, the correct answer, the
 * explanation and the AI judge's findings, plus "Why?" (EX-06) and "Report" (EX-07).
 */
export function AnswerFeedback({
  exercise,
  result,
}: {
  exercise: Exercise
  result: AttemptResult
}) {
  const partly = !result.correct && result.score > 0
  const Icon = result.correct ? CircleCheck : CircleX
  return (
    <section
      aria-label="Result"
      className={cn(
        'space-y-4 rounded-xl border p-5',
        result.correct
          ? 'border-success-soft-fg/30 bg-success-soft'
          : 'border-danger/30 bg-danger-soft',
      )}
    >
      <div
        role="status"
        className={cn(
          'flex items-center gap-2 text-lg font-semibold',
          result.correct ? 'text-success-soft-fg' : 'text-danger-soft-fg',
        )}
      >
        <Icon className="size-5" aria-hidden="true" />
        {result.correct
          ? 'Right!'
          : partly
            ? `Partly right (${formatScore(result.score)})`
            : 'Not quite'}
      </div>

      {result.notes.length > 0 && (
        <ul className="space-y-1 text-sm text-fg">
          {result.notes.map((note) => (
            <li key={note}>{NOTE_LABELS[note]}</li>
          ))}
        </ul>
      )}

      {(!result.correct || result.notes.includes('ALTERNATIVE')) && (
        <div className="text-fg">
          <p className="text-sm font-medium text-fg-muted">Correct answer</p>
          <p className="text-lg">
            <GermanText text={result.correctAnswer} />
          </p>
        </div>
      )}
      {result.alternatives.length > 0 && (
        <div className="text-sm text-fg">
          <p className="font-medium text-fg-muted">Also accepted</p>
          <ul className="list-inside list-disc">
            {result.alternatives.map((alternative) => (
              <li key={alternative}>{alternative}</li>
            ))}
          </ul>
        </div>
      )}

      {result.feedback && (
        <div className="space-y-2 text-fg">
          <p>{result.feedback.explanation}</p>
          {result.feedback.correctedAnswer && (
            <p>
              <span className="text-sm font-medium text-fg-muted">
                Your answer, corrected:{' '}
              </span>
              {result.feedback.correctedAnswer}
            </p>
          )}
          {result.feedback.errors.length > 0 && (
            <ul aria-label="Errors" className="space-y-1 text-sm">
              {result.feedback.errors.map((error, i) => (
                <li key={i}>
                  <span className="font-medium">{errorLabel(error.type)}</span>
                  {error.span && (
                    <>
                      {' '}
                      <q className="text-danger-soft-fg">{error.span}</q>
                    </>
                  )}
                  : {error.explanation}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {result.explanation && !result.feedback && (
        <p className="text-fg">{result.explanation}</p>
      )}

      <div className="flex flex-wrap gap-2">
        {!result.correct && <WhyButton exercise={exercise} result={result} />}
        <ReportButton exercise={exercise} />
      </div>
    </section>
  )
}

function WhyButton({
  exercise,
  result,
}: {
  exercise: Exercise
  result: AttemptResult
}) {
  const explain = useMutation({
    mutationFn: () => explainAttempt(exercise.id, result.attempt.id),
  })
  const explanation = explain.data
  if (explanation) {
    return (
      <div
        aria-label="Explanation"
        role="region"
        tabIndex={-1}
        // The button that asked is gone; read the explanation next
        ref={(element) => element?.focus()}
        style={{ outline: 'none' }}
        className="w-full space-y-2 rounded-lg border border-border bg-surface p-4 text-fg"
      >
        <p>{explanation.mistake}</p>
        <p>{explanation.rule}</p>
        {explanation.example && (
          <p className="text-sm">
            <GermanText text={explanation.example.sentence} />{' '}
            <span className="text-fg-muted">
              ({explanation.example.translation})
            </span>
          </p>
        )}
        {explanation.otherErrors.length > 0 && (
          <ul className="list-inside list-disc text-sm">
            {explanation.otherErrors.map((other) => (
              <li key={other}>{other}</li>
            ))}
          </ul>
        )}
      </div>
    )
  }
  return (
    <Button
      variant="secondary"
      size="sm"
      onClick={() => explain.mutate()}
      disabled={explain.isPending}
    >
      <Lightbulb className="size-4" aria-hidden="true" />
      {explain.isPending ? 'Explaining…' : 'Why is this wrong?'}
    </Button>
  )
}

function ReportButton({ exercise }: { exercise: Exercise }) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  const [reported, setReported] = useState(exercise.reported)
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const report = useMutation({
    mutationFn: () => reportExercise(exercise.id, reason),
    onSuccess: () => {
      setReported(true)
      setOpen(false)
      toast({ variant: 'success', title: 'Thanks, the exercise was reported' })
      void queryClient.invalidateQueries({ queryKey: EXERCISES_QUERY_KEY })
    },
    meta: { errorToast: false },
  })
  const withdraw = useMutation({
    mutationFn: () => withdrawReport(exercise.id),
    onSuccess: () => {
      setReported(false)
      void queryClient.invalidateQueries({ queryKey: EXERCISES_QUERY_KEY })
    },
  })

  if (reported) {
    return (
      <Button
        variant="ghost"
        size="sm"
        onClick={() => withdraw.mutate()}
        disabled={withdraw.isPending}
      >
        <Flag className="size-4" aria-hidden="true" />
        Reported · Undo
      </Button>
    )
  }
  return (
    <>
      <Button variant="ghost" size="sm" onClick={() => setOpen(true)}>
        <Flag className="size-4" aria-hidden="true" />
        Report a problem
      </Button>
      {open && (
        <Modal
          open
          title="Report this exercise"
          onClose={() => setOpen(false)}
          initialFocus={reasonRef}
        >
          <form
            noValidate
            className="space-y-4"
            onSubmit={(event) => {
              event.preventDefault()
              report.mutate()
            }}
          >
            <p className="text-fg-muted">
              Wrong solution, two right answers, or unclear? Reported exercises
              are left out of practice and help improve new ones.
            </p>
            <Field label="What's wrong? (optional)">
              <Textarea
                ref={reasonRef}
                value={reason}
                maxLength={1000}
                rows={3}
                onChange={(event) => setReason(event.target.value)}
              />
            </Field>
            {report.isError && (
              <Alert variant="danger">
                The report couldn't be sent. Try again.
              </Alert>
            )}
            <div className="flex justify-end gap-2">
              <Button
                variant="secondary"
                type="button"
                onClick={() => setOpen(false)}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={report.isPending}>
                Report
              </Button>
            </div>
          </form>
        </Modal>
      )}
    </>
  )
}
