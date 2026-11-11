import { zodResolver } from '@hookform/resolvers/zod'
import { useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { describeError } from '@/api/errors'
import {
  Alert,
  Button,
  Field,
  Input,
  Modal,
  Textarea,
  useToast,
} from '@/components/ui'
import { applyServerFieldErrors } from '@/lib/formErrors'
import { useInvalidateSessions } from '@/sessions/queries'
import { createSession } from '@/sessions/sessionsApi'
import { today } from './sessionFormat'
import { sessionDetailsSchema } from './sessionForms'

const newSessionSchema = sessionDetailsSchema.extend({ active: z.boolean() })

type NewSessionInput = z.input<typeof newSessionSchema>
type NewSessionValues = z.output<typeof newSessionSchema>

/** Starts a lesson and opens it (FE-SES-01). */
export function NewSessionModal({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const invalidate = useInvalidateSessions()
  const { toast } = useToast()
  const [formError, setFormError] = useState<string>()
  // The dialog opens in the title field, not on its close button
  const titleInput = useRef<HTMLInputElement>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<NewSessionInput, unknown, NewSessionValues>({
    resolver: zodResolver(newSessionSchema),
    defaultValues: { title: '', studiedOn: today(), notes: '', active: true },
  })
  const titleField = register('title')

  const onSubmit = handleSubmit(async (values) => {
    setFormError(undefined)
    try {
      const session = await createSession({
        title: values.title,
        studiedOn: values.studiedOn,
        notes: values.notes || undefined,
        active: values.active,
      })
      void invalidate()
      toast({ title: `Created “${session.title}”`, variant: 'success' })
      onClose()
      navigate(`/sessions/${session.id}`)
    } catch (error) {
      if (
        !applyServerFieldErrors(error, setError, [
          'title',
          'studiedOn',
          'notes',
        ])
      ) {
        setFormError(describeError(error))
      }
    }
  })

  return (
    <Modal
      open
      onClose={onClose}
      initialFocus={titleInput}
      title="New lesson"
      description="A lesson collects the words and grammar topics you studied together."
    >
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {formError && <Alert variant="danger">{formError}</Alert>}
        <Field label="Title" error={errors.title?.message} required>
          <Input
            autoComplete="off"
            placeholder="Lesson 5"
            {...titleField}
            ref={(element) => {
              titleField.ref(element)
              titleInput.current = element
            }}
          />
        </Field>
        <Field label="Date" error={errors.studiedOn?.message}>
          <Input type="date" {...register('studiedOn')} />
        </Field>
        <Field label="Notes" error={errors.notes?.message}>
          <Textarea rows={3} {...register('notes')} />
        </Field>
        <label className="flex items-start gap-3 text-sm text-fg">
          <input
            type="checkbox"
            className="mt-0.5 size-4 accent-primary"
            {...register('active')}
          />
          <span>
            <span className="font-medium">Make it the active lesson</span>
            <span className="block text-fg-muted">
              Words and grammar topics you add from now on join it
              automatically.
            </span>
          </span>
        </label>
        <div className="flex justify-end gap-3 border-t border-border pt-4">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting}>
            Create lesson
          </Button>
        </div>
      </form>
    </Modal>
  )
}
