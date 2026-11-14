import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { useAuth } from '@/auth/authContext'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-2">
      <h2 className="text-base font-semibold text-fg">{title}</h2>
      <div className="space-y-2 text-sm leading-relaxed text-fg-muted">
        {children}
      </div>
    </section>
  )
}

/**
 * /privacy (SEC-02): how LinguaFlow handles personal data, open to everyone. It states
 * what the app actually does; keep it in step with docs/security/privacy.md.
 */
export function PrivacyPage() {
  const { status } = useAuth()
  const signedIn = status === 'authenticated'

  return (
    <article className="space-y-6">
      <header className="space-y-1">
        <h1 className="text-xl font-semibold tracking-tight text-fg">
          Privacy
        </h1>
        <p className="text-sm text-fg-muted">Last updated 14 November 2026</p>
      </header>

      <Section title="What we store">
        <p>
          Your account: email address, display name, interface language, time
          zone and your password, which is stored only as a one-way hash.
        </p>
        <p>
          Your learning data: the words, grammar topics, notes and lessons you
          add, your flashcards and reviews, exercises and your answers to them,
          practice sessions and the progress worked out from them.
        </p>
        <p>
          For each AI request we keep a usage record (which feature, the number
          of tokens, the cost and whether it worked) so we can keep costs in
          check. It never contains the text of the request or the answer.
        </p>
      </Section>

      <Section title="What the AI sees">
        <p>
          AI features (filling in words, writing explanations and exercises,
          checking open answers) send learning content to our AI provider,
          Anthropic: the words and grammar topics involved, the exercise and
          your answer to it.
        </p>
        <p>
          They never send your name, email address or account details. Email
          addresses, phone numbers and long numbers in what you type are removed
          before anything is sent, and your personal notes are not sent at all.
        </p>
      </Section>

      <Section title="Cookies and browser storage">
        <p>
          One cookie keeps you logged in. It is only sent to the login service,
          and scripts can&apos;t read it. Your browser also remembers your theme
          and whether you like to type flashcard answers. There are no
          advertising, analytics or tracking cookies.
        </p>
      </Section>

      <Section title="How long we keep it">
        <p>
          Everything stays until you delete it or delete your account. Deleting
          your account removes all of your data at once, including your learning
          data and AI usage records.
        </p>
      </Section>

      <Section title="Your choices">
        <ul className="list-disc space-y-1 pl-5">
          <li>
            <strong className="font-medium text-fg">Download</strong> a copy of
            all your data in Settings, under “Your data”.
          </li>
          <li>
            <strong className="font-medium text-fg">Correct</strong> anything by
            editing it in the app.
          </li>
          <li>
            <strong className="font-medium text-fg">Delete</strong> your account
            and all its data in Settings, under “Delete account”.
          </li>
        </ul>
        <p>
          For anything else about your data, contact the people who run this
          LinguaFlow service.
        </p>
      </Section>

      <p className="text-sm">
        <Link
          to={signedIn ? '/settings' : '/register'}
          className="font-medium text-primary hover:underline"
        >
          {signedIn ? 'Back to settings' : 'Back to creating an account'}
        </Link>
      </p>
    </article>
  )
}
