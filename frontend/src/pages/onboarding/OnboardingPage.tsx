import { useMutation, useQueryClient } from '@tanstack/react-query'
import { type ReactNode, useEffect, useRef, useState } from 'react'
import { Navigate, useLocation } from 'react-router'
import { getErrorMessage, hasErrorCode, isNetworkError } from '@/api/errors'
import { useAuth } from '@/auth/authContext'
import { redirectTarget } from '@/auth/redirect'
import {
  Alert,
  Button,
  type RadioCardOption,
  RadioCards,
  Spinner,
  useToast,
} from '@/components/ui'
import {
  addLanguage,
  type AvailableLanguage,
  updateLanguage,
} from '@/languages/languagesApi'
import {
  MY_LANGUAGES_QUERY_KEY,
  useLanguages,
  useMyLanguages,
} from '@/languages/queries'
import {
  DAILY_GOAL_OPTIONS,
  type DailyGoal,
  DEFAULT_DAILY_GOAL,
  LEVEL_OPTIONS,
  type LevelChoice,
} from './onboardingOptions'

const STEPS = ['target', 'explanation', 'level', 'goal'] as const
type Step = (typeof STEPS)[number]

const STEP_TITLES: Record<Step, string> = {
  target: 'Which language do you want to learn?',
  explanation: 'Which language should we explain things in?',
  level: 'How well do you know it already?',
  goal: 'Pick a daily goal',
}

const STEP_HINTS: Record<Step, string> = {
  target: 'More languages are on the way.',
  explanation: 'Used for translations, grammar explanations and feedback.',
  level: 'This helps us pitch exercises at the right difficulty.',
  goal: 'Flashcard reviews per day. You can change this any time.',
}

function languageOption(language: AvailableLanguage): RadioCardOption<string> {
  return {
    value: language.code,
    label: language.name,
    description:
      language.nativeName !== language.name ? language.nativeName : undefined,
  }
}

/**
 * /onboarding: the first-login wizard. Adds the learner's first language with level and
 * daily goal; once the user has a language, this page sends them on (to `state.from`
 * or the dashboard), so it is never shown twice.
 */
export function OnboardingPage() {
  const myLanguages = useMyLanguages()
  const location = useLocation()

  if (myLanguages.isPending) return <CenteredSpinner />
  if (myLanguages.data && myLanguages.data.length > 0) {
    return <Navigate to={redirectTarget(location.state)} replace />
  }
  return <Wizard />
}

function Wizard() {
  const { user, logout } = useAuth()
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const languages = useLanguages()

  const [stepIndex, setStepIndex] = useState(0)
  const [target, setTarget] = useState<string>()
  const [explanation, setExplanation] = useState<string>()
  const [level, setLevel] = useState<LevelChoice>()
  const [goal, setGoal] = useState<DailyGoal>(DEFAULT_DAILY_GOAL)

  const heading = useRef<HTMLHeadingElement>(null)
  const firstRender = useRef(true)
  useEffect(() => {
    // Move focus to the new question, so screen readers announce it
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    heading.current?.focus()
  }, [stepIndex])

  const targets = (languages.data ?? []).filter((l) => l.supportedAsTarget)
  const chosenTarget = target ?? targets[0]?.code
  const explanations = (languages.data ?? []).filter(
    (l) => l.supportedAsExplanation && l.code !== chosenTarget,
  )
  const preferredExplanation =
    explanations.find((l) => l.code === user?.interfaceLanguage) ??
    explanations[0]
  const chosenExplanation =
    explanation && explanations.some((l) => l.code === explanation)
      ? explanation
      : preferredExplanation?.code

  const finish = useMutation({
    mutationFn: async () => {
      if (!chosenTarget || !chosenExplanation) return
      const selfAssessedLevel = level === 'unsure' ? undefined : level
      try {
        await addLanguage({
          languageCode: chosenTarget,
          explanationLanguageCode: chosenExplanation,
          selfAssessedLevel,
          active: true,
        })
      } catch (error) {
        // Added by an earlier attempt whose settings update failed: just update it
        if (!hasErrorCode(error, 'CONFLICT')) throw error
      }
      await updateLanguage(chosenTarget, {
        explanationLanguageCode: chosenExplanation,
        selfAssessedLevel,
        active: true,
        settings: { dailyReviewGoal: Number(goal) },
      })
    },
    onSuccess: async () => {
      toast({
        title: "You're all set",
        description: 'Start by adding a few words you want to learn.',
        variant: 'success',
      })
      // The page redirects as soon as the user has a language
      await queryClient.invalidateQueries({ queryKey: MY_LANGUAGES_QUERY_KEY })
    },
  })

  if (languages.isPending) return <CenteredSpinner />
  if (languages.isError || targets.length === 0) {
    return (
      <div className="space-y-4">
        <Alert variant="danger" title="Couldn't load the available languages">
          Check your connection and try again.
        </Alert>
        <Button
          variant="secondary"
          loading={languages.isFetching}
          onClick={() => void languages.refetch()}
        >
          Try again
        </Button>
      </div>
    )
  }

  const step = STEPS[stepIndex] ?? 'target'
  const isLast = stepIndex === STEPS.length - 1
  const answered: Record<Step, boolean> = {
    target: Boolean(chosenTarget),
    explanation: Boolean(chosenExplanation),
    level: Boolean(level),
    goal: true,
  }

  let question: ReactNode
  switch (step) {
    case 'target':
      question = (
        <RadioCards
          legend={STEP_TITLES.target}
          hideLegend
          options={targets.map(languageOption)}
          value={chosenTarget}
          onChange={setTarget}
        />
      )
      break
    case 'explanation':
      question = (
        <RadioCards
          legend={STEP_TITLES.explanation}
          hideLegend
          options={explanations.map(languageOption)}
          value={chosenExplanation}
          onChange={setExplanation}
        />
      )
      break
    case 'level':
      question = (
        <RadioCards
          legend={STEP_TITLES.level}
          hideLegend
          options={LEVEL_OPTIONS}
          value={level}
          onChange={setLevel}
          columns={2}
        />
      )
      break
    case 'goal':
      question = (
        <RadioCards
          legend={STEP_TITLES.goal}
          hideLegend
          options={DAILY_GOAL_OPTIONS}
          value={goal}
          onChange={setGoal}
        />
      )
      break
  }

  const errorMessage = finish.isError
    ? isNetworkError(finish.error)
      ? "Can't reach LinguaFlow right now. Check your connection and try again."
      : getErrorMessage(finish.error)
    : undefined

  return (
    <form
      className="space-y-6"
      onSubmit={(event) => {
        event.preventDefault()
        if (!answered[step]) return
        if (isLast) finish.mutate()
        else setStepIndex(stepIndex + 1)
      }}
    >
      <div className="space-y-3">
        <p className="text-sm font-medium text-primary">
          {stepIndex === 0 && user?.displayName
            ? `Welcome, ${user.displayName}! `
            : ''}
          Step {stepIndex + 1} of {STEPS.length}
        </p>
        <div
          className="h-1.5 overflow-hidden rounded-full bg-surface-muted"
          aria-hidden="true"
        >
          <div
            className="h-full rounded-full bg-primary transition-[width]"
            style={{ width: `${((stepIndex + 1) / STEPS.length) * 100}%` }}
          />
        </div>
        <h1
          ref={heading}
          tabIndex={-1}
          className="text-xl font-semibold tracking-tight text-fg focus:outline-none"
        >
          {STEP_TITLES[step]}
        </h1>
        <p className="text-sm text-fg-muted">{STEP_HINTS[step]}</p>
      </div>

      {question}

      {errorMessage && (
        <Alert variant="danger" title="Couldn't save your choices">
          {errorMessage}
        </Alert>
      )}

      <div className="flex items-center justify-between gap-3">
        {stepIndex > 0 ? (
          <Button
            variant="secondary"
            onClick={() => setStepIndex(stepIndex - 1)}
            disabled={finish.isPending}
          >
            Back
          </Button>
        ) : (
          <Button variant="ghost" size="sm" onClick={() => void logout()}>
            Log out
          </Button>
        )}
        <Button
          type="submit"
          disabled={!answered[step]}
          loading={finish.isPending}
        >
          {isLast ? 'Start learning' : 'Continue'}
        </Button>
      </div>
    </form>
  )
}

function CenteredSpinner() {
  return (
    <div role="status" className="flex justify-center py-10 text-fg-muted">
      <Spinner className="size-5" />
      <span className="sr-only">Loading…</span>
    </div>
  )
}
