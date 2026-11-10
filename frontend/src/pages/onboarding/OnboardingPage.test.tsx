import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LearnedLanguage } from '@/languages/languagesApi'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem } from '@/test/fakeApi'
import { findAlert } from '@/test/queries'
import { renderRoute, testLearnedLanguage } from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const continueButton = () => screen.getByRole('button', { name: 'Continue' })

/** Backend stand-in: GET returns what POST added, PATCH merges into it. */
function fakeLanguageBackend() {
  let mine: LearnedLanguage[] = []
  const bodies: { add: unknown[]; update: unknown[] } = { add: [], update: [] }
  fakeApi.on('GET', '/users/me/languages', () => ({ status: 200, data: mine }))
  fakeApi.on('POST', '/users/me/languages', (config) => {
    const body = JSON.parse(String(config.data)) as Record<string, unknown>
    bodies.add.push(body)
    mine = [{ ...testLearnedLanguage, selfAssessedLevel: undefined }]
    return { status: 201, data: mine[0] }
  })
  fakeApi.on('PATCH', '/users/me/languages/de', (config) => {
    const body = JSON.parse(String(config.data)) as Record<string, unknown>
    bodies.update.push(body)
    return { status: 200, data: mine[0] }
  })
  return bodies
}

async function startOnboarding(path = '/onboarding') {
  const view = renderRoute(path, { languages: [] })
  await screen.findByRole('heading', {
    name: 'Which language do you want to learn?',
  })
  return view
}

describe('onboarding', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('sends a user without a language to the wizard, remembering the page', async () => {
    const { router } = await startOnboarding('/vocabulary')

    expect(router.state.location.pathname).toBe('/onboarding')
    expect(router.state.location.state).toEqual({ from: '/vocabulary' })
    expect(screen.getByText(/Step 1 of 4/)).toBeInTheDocument()
    expect(screen.getByText(/Welcome, Anna!/)).toBeInTheDocument()
  })

  it('walks through all four steps and saves the choices', async () => {
    const bodies = fakeLanguageBackend()
    const { router } = await startOnboarding('/grammar')

    // 1. Target: German is the only one and preselected
    expect(screen.getByRole('radio', { name: /German/ })).toBeChecked()
    await userEvent.click(continueButton())

    // 2. Explanation: German itself is not offered
    expect(
      await screen.findByRole('heading', {
        name: 'Which language should we explain things in?',
      }),
    ).toHaveFocus()
    expect(screen.getAllByRole('radio')).toHaveLength(1)
    expect(screen.getByRole('radio', { name: /English/ })).toBeChecked()
    await userEvent.click(continueButton())

    // 3. Level: must be chosen
    await screen.findByRole('heading', {
      name: 'How well do you know it already?',
    })
    expect(continueButton()).toBeDisabled()
    await userEvent.click(screen.getByRole('radio', { name: /B1/ }))
    await userEvent.click(continueButton())

    // 4. Daily goal: 50 by default
    await screen.findByRole('heading', { name: 'Pick a daily goal' })
    expect(screen.getByRole('radio', { name: /50 reviews/ })).toBeChecked()
    await userEvent.click(screen.getByRole('radio', { name: /100 reviews/ }))
    await userEvent.click(
      screen.getByRole('button', { name: 'Start learning' }),
    )

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Grammar' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/grammar')
    expect(bodies.add).toEqual([
      {
        languageCode: 'de',
        explanationLanguageCode: 'en',
        selfAssessedLevel: 'B1',
        active: true,
      },
    ])
    expect(bodies.update).toEqual([
      {
        explanationLanguageCode: 'en',
        selfAssessedLevel: 'B1',
        active: true,
        settings: { dailyReviewGoal: 100 },
      },
    ])
    expect(screen.getByText("You're all set")).toBeInTheDocument()
  })

  it('sends no level when the learner is not sure', async () => {
    const bodies = fakeLanguageBackend()
    await startOnboarding()

    await userEvent.click(continueButton())
    await userEvent.click(continueButton())
    await userEvent.click(screen.getByRole('radio', { name: /not sure/ }))
    await userEvent.click(continueButton())
    await userEvent.click(
      screen.getByRole('button', { name: 'Start learning' }),
    )

    await screen.findByRole('heading', { level: 1, name: 'Dashboard' })
    expect(bodies.add[0]).not.toHaveProperty('selfAssessedLevel')
    expect(bodies.update[0]).toEqual({
      explanationLanguageCode: 'en',
      active: true,
      settings: { dailyReviewGoal: 50 },
    })
  })

  it('keeps earlier answers when going back', async () => {
    await startOnboarding()
    await userEvent.click(continueButton())
    await userEvent.click(continueButton())
    await userEvent.click(screen.getByRole('radio', { name: /C1/ }))
    await userEvent.click(continueButton())

    await userEvent.click(screen.getByRole('button', { name: 'Back' }))

    expect(screen.getByRole('radio', { name: /C1/ })).toBeChecked()
    expect(screen.getByText(/Step 3 of 4/)).toBeInTheDocument()
  })

  it('shows a save error and recovers on retry without adding twice', async () => {
    // First attempt: the language is added, but saving the settings fails
    let added = false
    let patchCalls = 0
    fakeApi.on('GET', '/users/me/languages', () => ({
      status: 200,
      data: added && patchCalls > 1 ? [testLearnedLanguage] : [],
    }))
    fakeApi.on('POST', '/users/me/languages', () => {
      if (added) return { status: 409, data: problem(409, 'CONFLICT') }
      added = true
      return { status: 201, data: testLearnedLanguage }
    })
    fakeApi.on('PATCH', '/users/me/languages/de', () => {
      patchCalls += 1
      return patchCalls === 1
        ? { status: 503, data: problem(503, 'SERVICE_UNAVAILABLE') }
        : { status: 200, data: testLearnedLanguage }
    })
    await startOnboarding()
    await userEvent.click(continueButton())
    await userEvent.click(continueButton())
    await userEvent.click(screen.getByRole('radio', { name: /A1/ }))
    await userEvent.click(continueButton())

    await userEvent.click(
      screen.getByRole('button', { name: 'Start learning' }),
    )
    expect(await findAlert()).toHaveTextContent("Couldn't save your choices")

    await userEvent.click(
      screen.getByRole('button', { name: 'Start learning' }),
    )
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Dashboard' }),
    ).toBeInTheDocument()
    expect(patchCalls).toBe(2)
  })

  it('skips the wizard for a user who already learns a language', async () => {
    const { router } = renderRoute('/onboarding')
    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/dashboard'),
    )
  })

  it('offers a retry when the languages cannot be loaded', async () => {
    let calls = 0
    fakeApi.on('GET', '/users/me/languages', () => {
      calls += 1
      return calls === 1
        ? { status: 0 }
        : { status: 200, data: [testLearnedLanguage] }
    })
    renderRoute('/dashboard')

    expect(await findAlert()).toHaveTextContent("Couldn't load your profile")
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Dashboard' }),
    ).toBeInTheDocument()
  })

  it('lets the user log out from the first step', async () => {
    fakeApi.on('POST', '/auth/logout', { status: 204 })
    const { router } = await startOnboarding()

    await userEvent.click(screen.getByRole('button', { name: 'Log out' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
  })
})
