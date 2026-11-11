import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { describeError, getFieldErrors } from '@/api/errors'
import {
  Alert,
  Badge,
  Button,
  ConfirmDialog,
  Field,
  Input,
  Select,
  useToast,
} from '@/components/ui'
import {
  type AvailableLanguage,
  CARD_DIRECTIONS,
  CEFR_LEVELS,
  EXERCISE_TYPES,
  type LearnedLanguage,
  removeLanguage,
  updateLanguage,
} from '@/languages/languagesApi'
import { MY_LANGUAGES_QUERY_KEY } from '@/languages/queries'
import {
  EXERCISE_TYPE_LABELS,
  type LearningSettingsValues,
  learningSettingsSchema,
} from './settingsForms'
import { SettingsSection } from './SettingsSection'

const SERVER_FIELDS: [string, keyof LearningSettingsValues][] = [
  ['explanationLanguageCode', 'explanationLanguageCode'],
  ['selfAssessedLevel', 'selfAssessedLevel'],
  ['settings.dailyReviewGoal', 'dailyReviewGoal'],
  ['settings.newCardsPerDay', 'newCardsPerDay'],
  ['settings.practiceSessionLength', 'practiceSessionLength'],
  ['settings.preferredExerciseTypes', 'preferredExerciseTypes'],
  ['settings.cardDirections', 'cardDirections'],
]

const valuesOf = (language: LearnedLanguage): LearningSettingsValues => ({
  explanationLanguageCode: language.explanationLanguageCode,
  selfAssessedLevel: language.selfAssessedLevel ?? '',
  dailyReviewGoal: language.settings.dailyReviewGoal,
  newCardsPerDay: language.settings.newCardsPerDay,
  practiceSessionLength: language.settings.practiceSessionLength,
  preferredExerciseTypes: language.settings.preferredExerciseTypes,
  cardDirections: language.settings.cardDirections,
})

interface LanguageSettingsSectionProps {
  language: LearnedLanguage
  catalog: AvailableLanguage[]
  /** The user learns more than one language (shows "Make active") */
  hasOthers: boolean
}

/** Level, explanation language and learning settings for one learning language. */
export function LanguageSettingsSection({
  language,
  catalog,
  hasOthers,
}: LanguageSettingsSectionProps) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [formError, setFormError] = useState<string>()
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [activating, setActivating] = useState(false)

  const name =
    catalog.find((l) => l.code === language.languageCode)?.name ??
    language.languageCode
  const explanationName =
    catalog.find((l) => l.code === language.explanationLanguageCode)?.name ??
    language.explanationLanguageCode
  const explanationOptions = catalog
    .filter((l) => l.supportedAsExplanation && l.code !== language.languageCode)
    .map((l) => ({ value: l.code, label: l.name }))

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<LearningSettingsValues>({
    resolver: zodResolver(learningSettingsSchema),
    defaultValues: valuesOf(language),
  })

  const storeUpdated = (updated: LearnedLanguage) =>
    queryClient.setQueryData<LearnedLanguage[]>(MY_LANGUAGES_QUERY_KEY, (old) =>
      old?.map((l) => (l.languageCode === updated.languageCode ? updated : l)),
    )

  const onSubmit = handleSubmit(async (values) => {
    setFormError(undefined)
    try {
      const updated = await updateLanguage(language.languageCode, {
        explanationLanguageCode: values.explanationLanguageCode,
        selfAssessedLevel: values.selfAssessedLevel || undefined,
        settings: {
          dailyReviewGoal: values.dailyReviewGoal,
          newCardsPerDay: values.newCardsPerDay,
          practiceSessionLength: values.practiceSessionLength,
          preferredExerciseTypes: values.preferredExerciseTypes,
          cardDirections: values.cardDirections,
        },
      })
      storeUpdated(updated)
      reset(valuesOf(updated))
      toast({ title: `${name} settings saved`, variant: 'success' })
    } catch (error) {
      // The backend names nested settings fields "settings.<name>"
      const serverErrors = getFieldErrors(error)
      let applied = false
      for (const [serverField, field] of SERVER_FIELDS) {
        const message = serverErrors[serverField]
        if (message) {
          setError(
            field,
            { type: 'server', message },
            { shouldFocus: !applied },
          )
          applied = true
        }
      }
      if (!applied) setFormError(describeError(error))
    }
  })

  const makeActive = async () => {
    setActivating(true)
    try {
      await updateLanguage(language.languageCode, { active: true })
      // Activating one deactivates the others, so reload the whole list
      await queryClient.invalidateQueries({ queryKey: MY_LANGUAGES_QUERY_KEY })
      toast({ title: `Now learning ${name}`, variant: 'success' })
    } catch (error) {
      toast({ title: describeError(error), variant: 'error' })
    } finally {
      setActivating(false)
    }
  }

  const remove = async () => {
    try {
      await removeLanguage(language.languageCode)
      setConfirmRemove(false)
      toast({ title: `${name} removed`, variant: 'success' })
      // Without any language left, the onboarding gate takes over
      await queryClient.invalidateQueries({ queryKey: MY_LANGUAGES_QUERY_KEY })
    } catch (error) {
      setConfirmRemove(false)
      toast({ title: describeError(error), variant: 'error' })
    }
  }

  return (
    <SettingsSection
      title={name}
      description="Your level, explanations and daily targets for this language."
      aside={
        language.active ? <Badge variant="primary">Active</Badge> : undefined
      }
    >
      <form onSubmit={onSubmit} noValidate className="space-y-5">
        {formError && <Alert variant="danger">{formError}</Alert>}

        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label="Explanations in"
            hint="Translations, grammar explanations and feedback."
            error={errors.explanationLanguageCode?.message}
          >
            <Select
              options={explanationOptions}
              {...register('explanationLanguageCode')}
            />
          </Field>
          <Field label="Your level" error={errors.selfAssessedLevel?.message}>
            <Select
              placeholder={language.selfAssessedLevel ? undefined : 'Not set'}
              options={CEFR_LEVELS.map((level) => ({
                value: level,
                label: level,
              }))}
              {...register('selfAssessedLevel')}
            />
          </Field>
        </div>

        <div className="grid gap-4 sm:grid-cols-3">
          <Field
            label="Daily review goal"
            hint="Flashcard reviews per day (1–1000)."
            error={errors.dailyReviewGoal?.message}
          >
            <Input
              type="number"
              inputMode="numeric"
              min={1}
              max={1000}
              {...register('dailyReviewGoal', { valueAsNumber: true })}
            />
          </Field>
          <Field
            label="New cards per day"
            hint="0–500. Set 0 to only review."
            error={errors.newCardsPerDay?.message}
          >
            <Input
              type="number"
              inputMode="numeric"
              min={0}
              max={500}
              {...register('newCardsPerDay', { valueAsNumber: true })}
            />
          </Field>
          <Field
            label="Practice session length"
            hint="Items per session (5–200)."
            error={errors.practiceSessionLength?.message}
          >
            <Input
              type="number"
              inputMode="numeric"
              min={5}
              max={200}
              {...register('practiceSessionLength', { valueAsNumber: true })}
            />
          </Field>
        </div>

        <fieldset>
          <legend className="text-sm font-medium text-fg">
            Preferred exercise types
          </legend>
          <p className="mt-1 text-xs text-fg-subtle">
            Leave all unticked for a mix of everything.
          </p>
          <div className="mt-3 grid gap-2 sm:grid-cols-2">
            {EXERCISE_TYPES.map((type) => (
              <label
                key={type}
                className="flex items-center gap-2 text-sm text-fg"
              >
                <input
                  type="checkbox"
                  value={type}
                  className="size-4 accent-primary"
                  {...register('preferredExerciseTypes')}
                />
                {EXERCISE_TYPE_LABELS[type]}
              </label>
            ))}
          </div>
        </fieldset>

        <fieldset>
          <legend className="text-sm font-medium text-fg">Flashcards</legend>
          <p className="mt-1 text-xs text-fg-subtle">
            Which way your cards ask. Cards exist both ways, so switching keeps
            your progress.
          </p>
          <div className="mt-3 grid gap-2 sm:grid-cols-2">
            {CARD_DIRECTIONS.map((direction) => (
              <label
                key={direction}
                className="flex items-center gap-2 text-sm text-fg"
              >
                <input
                  type="checkbox"
                  value={direction}
                  className="size-4 accent-primary"
                  {...register('cardDirections')}
                />
                {direction === 'TARGET_TO_NATIVE'
                  ? `${name} → ${explanationName}`
                  : `${explanationName} → ${name}`}
              </label>
            ))}
          </div>
          {errors.cardDirections?.message && (
            <p className="mt-2 text-xs font-medium text-danger">
              {errors.cardDirections.message}
            </p>
          )}
        </fieldset>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
          <div className="flex gap-2">
            <button
              type="button"
              className="inline-flex h-8 items-center rounded-md px-3 text-sm font-medium text-danger hover:bg-danger-soft"
              onClick={() => setConfirmRemove(true)}
            >
              Remove {name}
            </button>
            {hasOthers && !language.active && (
              <Button
                variant="secondary"
                size="sm"
                loading={activating}
                onClick={() => void makeActive()}
              >
                Make active
              </Button>
            )}
          </div>
          <Button type="submit" loading={isSubmitting} disabled={!isDirty}>
            Save {name} settings
          </Button>
        </div>
      </form>

      <ConfirmDialog
        open={confirmRemove}
        onCancel={() => setConfirmRemove(false)}
        onConfirm={remove}
        destructive
        title={`Remove ${name}?`}
        description={
          <>
            This permanently deletes all your {name} vocabulary, grammar topics,
            flashcards, sessions and progress. It can't be undone.
            {!hasOthers &&
              " It's your only language, so you'll choose one to learn again."}
          </>
        }
        confirmLabel={`Remove ${name}`}
      />
    </SettingsSection>
  )
}
