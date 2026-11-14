import { useMutation } from '@tanstack/react-query'
import { Download } from 'lucide-react'
import { Link } from 'react-router'
import { downloadMyData } from '@/account/accountApi'
import { Button } from '@/components/ui'
import { SettingsSection } from './SettingsSection'

/** Settings card for the learner's data (SEC-02): download it all, read how it's handled. */
export function YourDataSection() {
  const download = useMutation({
    mutationFn: downloadMyData,
    meta: { errorTitle: "Couldn't download your data" },
  })

  return (
    <SettingsSection
      title="Your data"
      description="Download a copy of everything LinguaFlow stores about you."
    >
      <div className="space-y-4">
        <p className="text-sm text-fg-muted">
          One JSON file with your account, languages and settings, words,
          grammar topics, lessons, flashcards and reviews, exercises and
          answers, practice sessions, progress, and your AI usage. Your password
          is never included.
        </p>
        <div className="flex flex-wrap items-center gap-4">
          <Button
            variant="secondary"
            loading={download.isPending}
            onClick={() => {
              download.mutate()
            }}
          >
            <Download className="size-4" aria-hidden="true" />
            Download my data
          </Button>
          <Link
            to="/privacy"
            className="text-sm font-medium text-primary hover:underline"
          >
            How we handle your data
          </Link>
        </div>
        {download.isSuccess && (
          <p role="status" className="text-sm text-success-soft-fg">
            Your data was downloaded.
          </p>
        )}
      </div>
    </SettingsSection>
  )
}
