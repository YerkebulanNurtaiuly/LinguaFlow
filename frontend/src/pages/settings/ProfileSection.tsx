import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { getErrorMessage } from '@/api/errors'
import { updateProfile } from '@/account/accountApi'
import type { CurrentUser } from '@/auth/authApi'
import { CURRENT_USER_QUERY_KEY } from '@/auth/authContext'
import { Alert, Button, Field, Input, Select, useToast } from '@/components/ui'
import type { AvailableLanguage } from '@/languages/languagesApi'
import { applyServerFieldErrors } from '@/lib/formErrors'
import {
  browserTimeZone,
  type ProfileValues,
  profileSchema,
  timeZoneOptions,
} from './settingsForms'
import { SettingsSection } from './SettingsSection'

const valuesOf = (user: CurrentUser): ProfileValues => ({
  displayName: user.displayName ?? '',
  interfaceLanguage: user.interfaceLanguage,
  timezone: user.timezone,
})

export function ProfileSection({
  user,
  catalog,
}: {
  user: CurrentUser
  catalog: AvailableLanguage[]
}) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [formError, setFormError] = useState<string>()
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    reset,
    control,
    formState: { errors, isSubmitting, isDirty, dirtyFields },
  } = useForm<ProfileValues>({
    resolver: zodResolver(profileSchema),
    defaultValues: valuesOf(user),
  })

  const deviceZone = browserTimeZone()
  const timezone = useWatch({ control, name: 'timezone' })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(undefined)
    try {
      const updated = await updateProfile({
        displayName: dirtyFields.displayName ? values.displayName : undefined,
        interfaceLanguage: dirtyFields.interfaceLanguage
          ? values.interfaceLanguage
          : undefined,
        timezone: dirtyFields.timezone ? values.timezone : undefined,
      })
      queryClient.setQueryData(CURRENT_USER_QUERY_KEY, updated)
      reset(valuesOf(updated))
      toast({ title: 'Profile saved', variant: 'success' })
    } catch (error) {
      if (
        !applyServerFieldErrors(
          error,
          setError,
          ['displayName', 'interfaceLanguage', 'timezone'],
          {
            displayName: 'Name',
            interfaceLanguage: 'Language',
            timezone: 'Time zone',
          },
        )
      ) {
        setFormError(getErrorMessage(error))
      }
    }
  })

  return (
    <SettingsSection
      title="Profile"
      description="How LinguaFlow addresses you, and your local time for daily goals."
    >
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {formError && <Alert variant="danger">{formError}</Alert>}
        <Field label="Email" hint="Your email address can't be changed yet.">
          <Input value={user.email} readOnly disabled />
        </Field>
        <Field label="Name" error={errors.displayName?.message}>
          <Input autoComplete="name" {...register('displayName')} />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="App language" error={errors.interfaceLanguage?.message}>
            <Select
              options={catalog.map((language) => ({
                value: language.code,
                label: language.name,
              }))}
              {...register('interfaceLanguage')}
            />
          </Field>
          <Field
            label="Time zone"
            hint={
              deviceZone && deviceZone !== timezone ? (
                <button
                  type="button"
                  className="text-primary hover:underline"
                  onClick={() =>
                    setValue('timezone', deviceZone, { shouldDirty: true })
                  }
                >
                  Use this device's time zone ({deviceZone})
                </button>
              ) : (
                'Your day starts at midnight in this time zone.'
              )
            }
            error={errors.timezone?.message}
          >
            <Select
              options={timeZoneOptions(user.timezone).map((zone) => ({
                value: zone,
                label: zone.replaceAll('_', ' '),
              }))}
              {...register('timezone')}
            />
          </Field>
        </div>
        <div className="flex justify-end">
          <Button type="submit" loading={isSubmitting} disabled={!isDirty}>
            Save profile
          </Button>
        </div>
      </form>
    </SettingsSection>
  )
}
