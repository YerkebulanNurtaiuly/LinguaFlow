import { useQueryClient } from '@tanstack/react-query'
import { Sparkles } from 'lucide-react'
import { useEffect, useState } from 'react'
import { describeError } from '@/api/errors'
import { useAiJob, type Job } from '@/ai/aiJobsApi'
import { Alert, Button, Spinner } from '@/components/ui'
import { requestExplanation, type GrammarTopic } from '@/grammar/grammarApi'
import { grammarTopicKey } from '@/grammar/queries'
import { SettingsSection } from '@/pages/settings/SettingsSection'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })
const timeFormat = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' })

/** What to tell the learner while the AI is (or isn't yet) writing. */
function progressText(job: Job | undefined): string {
  if (job?.waiting === 'DAILY_LIMIT') {
    const at = job.nextRunAt
      ? ` at ${timeFormat.format(new Date(job.nextRunAt))}`
      : ''
    return `You've used today's AI allowance. The explanation will be written when it resets${at}.`
  }
  if (job?.waiting === 'RETRY') {
    return 'The AI service had a hiccup. Trying again shortly…'
  }
  return 'Writing an explanation… this usually takes under a minute.'
}

/**
 * The AI-written explanation (GRM-04), kept apart from the learner's notes. The learner
 * asks for it; it's written in the background, followed here until it's done.
 */
export function AiExplanationSection({ topic }: { topic: GrammarTopic }) {
  const explanation = topic.aiExplanation
  const queryClient = useQueryClient()
  const [jobId, setJobId] = useState<string>()
  const [requesting, setRequesting] = useState(false)
  const [requestError, setRequestError] = useState<string>()
  const job = useAiJob(jobId)

  const status = job.data?.status
  const writing = jobId !== undefined && !job.data?.finished
  const failed = status === 'FAILED' || status === 'DEAD'

  // Once written, reload the topic so the new explanation shows
  useEffect(() => {
    if (status === 'SUCCEEDED') {
      void queryClient.invalidateQueries({
        queryKey: grammarTopicKey(topic.id),
      })
    }
  }, [status, queryClient, topic.id])

  const ask = async () => {
    setRequestError(undefined)
    setRequesting(true)
    try {
      setJobId((await requestExplanation(topic.id)).aiJobId)
    } catch (error) {
      setRequestError(describeError(error))
    } finally {
      setRequesting(false)
    }
  }

  return (
    <SettingsSection
      title="AI explanation"
      aside={<Sparkles className="size-4 text-primary" aria-hidden="true" />}
      description={
        explanation && topic.aiExplanationGeneratedAt
          ? `Written by AI on ${dateFormat.format(new Date(topic.aiExplanationGeneratedAt))}. Your notes stay separate.`
          : undefined
      }
    >
      <div className="space-y-4 text-sm">
        {requestError && <Alert variant="danger">{requestError}</Alert>}
        {failed && (
          <Alert variant="danger" title="The AI couldn't write an explanation">
            Try again in a little while.
          </Alert>
        )}
        {writing && (
          <div role="status" className="flex items-center gap-2 text-fg-muted">
            <Spinner />
            {progressText(job.data)}
          </div>
        )}

        {explanation ? (
          <ExplanationView explanation={explanation} />
        ) : (
          !writing && (
            <p className="text-fg-muted">
              Get a short explanation of this topic with rules, examples and
              common mistakes, written for your level. It stays separate from
              your notes.
            </p>
          )
        )}

        {!writing && (
          <Button
            variant={explanation ? 'secondary' : 'primary'}
            size="sm"
            onClick={() => void ask()}
            loading={requesting}
          >
            <Sparkles className="size-4" aria-hidden="true" />
            {explanation ? 'Write a new explanation' : 'Explain with AI'}
          </Button>
        )}
      </div>
    </SettingsSection>
  )
}

function ExplanationView({
  explanation,
}: {
  explanation: NonNullable<GrammarTopic['aiExplanation']>
}) {
  return (
    <div className="space-y-4">
      {explanation.summary && <p className="text-fg">{explanation.summary}</p>}
      {explanation.rules.length > 0 && (
        <div>
          <h3 className="font-medium text-fg">Rules</h3>
          <ul className="mt-1 list-disc space-y-2 pl-5 text-fg">
            {explanation.rules.map((rule, index) => (
              // Rules may hold a small table written as text, one row per line
              <li key={index} className="whitespace-pre-line">
                {rule}
              </li>
            ))}
          </ul>
        </div>
      )}
      {explanation.examples.length > 0 && (
        <div>
          <h3 className="font-medium text-fg">Examples</h3>
          <ul className="mt-1 space-y-2">
            {explanation.examples.map((example, index) => (
              <li key={index}>
                <p lang="de" className="text-fg">
                  {example.sentence}
                </p>
                {example.translation && (
                  <p className="text-fg-muted">{example.translation}</p>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      {explanation.commonMistakes.length > 0 && (
        <div>
          <h3 className="font-medium text-fg">Common mistakes</h3>
          <ul className="mt-1 list-disc space-y-1 pl-5 text-fg">
            {explanation.commonMistakes.map((mistake, index) => (
              <li key={index}>{mistake}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
