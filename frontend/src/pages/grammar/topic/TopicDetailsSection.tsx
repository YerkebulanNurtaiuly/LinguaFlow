import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { describeError, hasErrorCode } from '@/api/errors'
import type { UpdateGrammarTopicRequest } from '@/api/types'
import {
  Alert,
  Button,
  Field,
  Input,
  Select,
  Textarea,
  useToast,
} from '@/components/ui'
import type { GrammarTopic } from '@/grammar/grammarApi'
import { CEFR_LEVELS } from '@/languages/languagesApi'
import { applyServerFieldErrors } from '@/lib/formErrors'
import { SettingsSection } from '@/pages/settings/SettingsSection'
import { LEVEL_NAMES } from '../grammarLabels'
import { useSaveTopic } from './useSaveTopic'

const detailsSchema = z.object({
  title: z
    .string()
    .trim()
    .min(1, 'Enter a title.')
    .max(200, 'The title can be at most 200 characters.'),
  cefrLevel: z.union([z.enum(CEFR_LEVELS), z.literal('')]),
  description: z
    .string()
    .max(2000, 'The description can be at most 2000 characters.')
    .transform((value) => value.trim()),
})

type DetailsInput = z.input<typeof detailsSchema>
type DetailsValues = z.output<typeof detailsSchema>

const valuesOf = (topic: GrammarTopic): DetailsInput => ({
  title: topic.title,
  cefrLevel: topic.cefrLevel ?? '',
  description: topic.description ?? '',
})

/** Title, level and description; only changed fields are sent. */
export function TopicDetailsSection({ topic }: { topic: GrammarTopic }) {
  const save = useSaveTopic(topic.id)
  const { toast } = useToast()
  const [formError, setFormError] = useState<string>()
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<DetailsInput, unknown, DetailsValues>({
    resolver: zodResolver(detailsSchema),
    defaultValues: valuesOf(topic),
  })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(undefined)
    const original = valuesOf(topic)
    const request: UpdateGrammarTopicRequest = {}
    if (values.title !== original.title) request.title = values.title
    // The API can't clear a level, so the select has no empty option once one is set
    if (values.cefrLevel && values.cefrLevel !== original.cefrLevel)
      request.cefrLevel = values.cefrLevel
    if (values.description !== original.description)
      request.description = values.description
    try {
      const updated = await save(request)
      reset(valuesOf(updated))
      toast({ title: 'Details saved', variant: 'success' })
    } catch (error) {
      if (hasErrorCode(error, 'GRAMMAR_TOPIC_DUPLICATE')) {
        setError(
          'title',
          {
            type: 'server',
            message: `You already have a topic called “${values.title}”.`,
          },
          { shouldFocus: true },
        )
        return
      }
      if (
        !applyServerFieldErrors(error, setError, [
          'title',
          'cefrLevel',
          'description',
        ])
      ) {
        setFormError(describeError(error))
      }
    }
  })

  return (
    <SettingsSection title="Details">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {formError && <Alert variant="danger">{formError}</Alert>}
        <div className="grid gap-4 sm:grid-cols-[2fr_1fr]">
          <Field label="Title" error={errors.title?.message} required>
            <Input autoComplete="off" {...register('title')} />
          </Field>
          <Field label="Level" error={errors.cefrLevel?.message}>
            <Select
              placeholder={topic.cefrLevel ? undefined : 'Not set'}
              options={CEFR_LEVELS.map((level) => ({
                value: level,
                label: `${level} · ${LEVEL_NAMES[level]}`,
              }))}
              {...register('cefrLevel')}
            />
          </Field>
        </div>
        <Field label="Description" error={errors.description?.message}>
          <Textarea rows={3} {...register('description')} />
        </Field>
        <div className="flex justify-end">
          <Button type="submit" loading={isSubmitting} disabled={!isDirty}>
            Save details
          </Button>
        </div>
      </form>
    </SettingsSection>
  )
}
