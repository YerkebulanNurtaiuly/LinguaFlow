import { describeError } from '@/api/errors'
import { useAuth } from '@/auth/authContext'
import { LoadError, PageSkeleton } from '@/components/ui'
import { useLanguages, useMyLanguages } from '@/languages/queries'
import { DeleteAccountSection } from './DeleteAccountSection'
import { LanguageSettingsSection } from './LanguageSettingsSection'
import { PasswordSection } from './PasswordSection'
import { ProfileSection } from './ProfileSection'

/** /settings: profile, learning languages, password and account deletion. */
export function SettingsPage() {
  const { user } = useAuth()
  const catalog = useLanguages()
  const myLanguages = useMyLanguages()

  const failed = catalog.isError || myLanguages.isError
  const ready = user && catalog.data && myLanguages.data

  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
        <p className="mt-2 text-fg-muted">
          Profile, languages and learning settings.
        </p>
      </div>

      {failed ? (
        <LoadError
          title="Couldn't load your settings"
          description={describeError(catalog.error ?? myLanguages.error)}
          retrying={catalog.isFetching || myLanguages.isFetching}
          onRetry={() => {
            if (catalog.isError) void catalog.refetch()
            if (myLanguages.isError) void myLanguages.refetch()
          }}
        />
      ) : !ready ? (
        <PageSkeleton label="Loading settings" cards={3} />
      ) : (
        <>
          <ProfileSection user={user} catalog={catalog.data} />
          {myLanguages.data.map((language) => (
            <LanguageSettingsSection
              key={language.languageCode}
              language={language}
              catalog={catalog.data}
              hasOthers={myLanguages.data.length > 1}
            />
          ))}
          <PasswordSection email={user.email} />
          <DeleteAccountSection />
        </>
      )}
    </div>
  )
}
