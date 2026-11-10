import { useAuth } from '@/auth/authContext'
import { Alert, Button, SkeletonText } from '@/components/ui'
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
        <div className="space-y-3">
          <Alert variant="danger" title="Couldn't load your settings">
            Check your connection and try again.
          </Alert>
          <Button
            variant="secondary"
            onClick={() => {
              void catalog.refetch()
              void myLanguages.refetch()
            }}
          >
            Try again
          </Button>
        </div>
      ) : !ready ? (
        <div role="status" aria-label="Loading settings" className="space-y-6">
          <SkeletonText lines={4} />
          <SkeletonText lines={6} />
        </div>
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
