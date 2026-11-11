import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { tokenStore } from '@/auth/tokenStore'
import { fetchHealth } from '@/lib/health'
import { fakeApi, problem, sentBody } from '@/test/fakeApi'
import { findAlert } from '@/test/queries'
import {
  renderApp,
  testLearnedLanguage,
  testUser,
} from '@/test/renderWithProviders'

vi.mock('@/lib/health', () => ({ fetchHealth: vi.fn() }))

const section = (name: string) => screen.getByRole('region', { name })

function validationError(field: string, message: string) {
  return {
    status: 400,
    data: {
      ...problem(400, 'VALIDATION_FAILED'),
      errors: [{ field, message }],
    },
  }
}

async function openSettings() {
  const view = await renderApp('/settings')
  await screen.findByRole('heading', { name: 'Profile' })
  return view
}

describe('settings page', () => {
  beforeEach(() => {
    vi.mocked(fetchHealth).mockResolvedValue({ status: 'UP' })
  })

  it('shows the current profile and learning settings', async () => {
    await openSettings()

    const profile = section('Profile')
    expect(within(profile).getByLabelText('Name')).toHaveValue('Anna')
    expect(within(profile).getByLabelText('Email')).toHaveValue(testUser.email)
    expect(within(profile).getByLabelText('Time zone')).toHaveValue(
      'Europe/Berlin',
    )

    const german = section('German')
    expect(within(german).getByText('Active')).toBeInTheDocument()
    expect(within(german).getByLabelText('Your level')).toHaveValue('A2')
    expect(within(german).getByLabelText('Daily review goal')).toHaveValue(50)
    expect(
      within(german).getByRole('button', { name: 'Save German settings' }),
    ).toBeDisabled()
  })

  describe('profile', () => {
    it('saves only the changed fields and updates the sidebar', async () => {
      fakeApi.on('PATCH', '/users/me', (config) => ({
        status: 200,
        data: { ...testUser, ...JSON.parse(String(config.data)) },
      }))
      await openSettings()
      const profile = section('Profile')

      const name = within(profile).getByLabelText('Name')
      await userEvent.clear(name)
      await userEvent.type(name, 'Anna Schmidt')
      await userEvent.click(
        within(profile).getByRole('button', { name: 'Save profile' }),
      )

      expect(await screen.findByText('Profile saved')).toBeInTheDocument()
      expect(sentBody('PATCH', '/users/me')).toEqual({
        displayName: 'Anna Schmidt',
      })
      expect(
        within(screen.getByRole('complementary')).getByText('Anna Schmidt'),
      ).toBeInTheDocument()
      expect(
        within(profile).getByRole('button', { name: 'Save profile' }),
      ).toBeDisabled()
    })

    it('shows a server error next to the field', async () => {
      fakeApi.on(
        'PATCH',
        '/users/me',
        validationError('timezone', 'must be an IANA time zone'),
      )
      await openSettings()
      const profile = section('Profile')

      await userEvent.selectOptions(
        within(profile).getByLabelText('Time zone'),
        'Asia/Tokyo',
      )
      await userEvent.click(
        within(profile).getByRole('button', { name: 'Save profile' }),
      )

      expect(
        await within(profile).findByText(
          'Time zone must be an IANA time zone.',
        ),
      ).toBeInTheDocument()
    })
  })

  describe('password', () => {
    async function fillPassword(current: string, next: string, repeat = next) {
      const password = section('Password')
      await userEvent.type(
        within(password).getByLabelText('Current password'),
        current,
      )
      await userEvent.type(
        within(password).getByLabelText('New password'),
        next,
      )
      await userEvent.type(
        within(password).getByLabelText('Repeat new password'),
        repeat,
      )
      await userEvent.click(
        within(password).getByRole('button', { name: 'Change password' }),
      )
      return password
    }

    it('checks that the new passwords match', async () => {
      await openSettings()

      const password = await fillPassword(
        'old-password-1',
        'new-password-123',
        'new-password-321',
      )

      expect(
        await within(password).findByText("Passwords don't match"),
      ).toBeInTheDocument()
      expect(fakeApi.requests('/users/me/password')).toHaveLength(0)
    })

    it('reports a wrong current password', async () => {
      fakeApi.on(
        'PUT',
        '/users/me/password',
        validationError('currentPassword', 'is incorrect'),
      )
      await openSettings()

      const password = await fillPassword('wrong-password', 'new-password-123')

      expect(
        await within(password).findByText('Current password is incorrect.'),
      ).toBeInTheDocument()
      expect(within(password).getByLabelText('Current password')).toHaveFocus()
    })

    it('changes the password and keeps this device logged in', async () => {
      fakeApi.on('PUT', '/users/me/password', {
        status: 200,
        data: {
          accessToken: 'after-password-change',
          tokenType: 'Bearer',
          expiresIn: 900,
          expiresAt: '2026-11-10T10:15:00Z',
        },
      })
      await openSettings()

      const password = await fillPassword('old-password-1', 'new-password-123')

      expect(await screen.findByText('Password changed')).toBeInTheDocument()
      expect(sentBody('PUT', '/users/me/password')).toEqual({
        currentPassword: 'old-password-1',
        newPassword: 'new-password-123',
      })
      expect(tokenStore.get()).toBe('after-password-change')
      expect(within(password).getByLabelText('Current password')).toHaveValue(
        '',
      )
    })
  })

  describe('learning settings', () => {
    it('saves level, goals and preferred exercise types', async () => {
      fakeApi.on('PATCH', '/users/me/languages/de', {
        status: 200,
        data: {
          ...testLearnedLanguage,
          selfAssessedLevel: 'B1',
          settings: {
            ...testLearnedLanguage.settings,
            dailyReviewGoal: 80,
            preferredExerciseTypes: ['TRANSLATION'],
          },
        },
      })
      await openSettings()
      const german = section('German')

      await userEvent.selectOptions(
        within(german).getByLabelText('Your level'),
        'B1',
      )
      const goal = within(german).getByLabelText('Daily review goal')
      await userEvent.clear(goal)
      await userEvent.type(goal, '80')
      await userEvent.click(within(german).getByLabelText('Translation'))
      await userEvent.click(
        within(german).getByRole('button', { name: 'Save German settings' }),
      )

      expect(
        await screen.findByText('German settings saved'),
      ).toBeInTheDocument()
      expect(sentBody('PATCH', '/users/me/languages/de')).toEqual({
        explanationLanguageCode: 'en',
        selfAssessedLevel: 'B1',
        settings: {
          dailyReviewGoal: 80,
          newCardsPerDay: 20,
          practiceSessionLength: 20,
          preferredExerciseTypes: ['TRANSLATION'],
          cardDirections: ['TARGET_TO_NATIVE', 'NATIVE_TO_TARGET'],
        },
      })
    })

    it('chooses which flashcard directions to review, at least one', async () => {
      fakeApi.on('PATCH', '/users/me/languages/de', {
        status: 200,
        data: {
          ...testLearnedLanguage,
          settings: {
            ...testLearnedLanguage.settings,
            cardDirections: ['TARGET_TO_NATIVE'],
          },
        },
      })
      await openSettings()
      const german = within(section('German'))
      const toGerman = german.getByLabelText('English → German')
      const fromGerman = german.getByLabelText('German → English')
      expect(toGerman).toBeChecked()
      expect(fromGerman).toBeChecked()

      await userEvent.click(toGerman)
      await userEvent.click(fromGerman)
      const save = german.getByRole('button', { name: 'Save German settings' })
      await userEvent.click(save)
      expect(
        await german.findByText('Choose at least one direction to review.'),
      ).toBeInTheDocument()

      await userEvent.click(fromGerman)
      await userEvent.click(save)
      expect(
        await screen.findByText('German settings saved'),
      ).toBeInTheDocument()
      expect(
        (sentBody('PATCH', '/users/me/languages/de') as { settings: object })
          .settings,
      ).toMatchObject({ cardDirections: ['TARGET_TO_NATIVE'] })
    })

    it('rejects out-of-range numbers before saving', async () => {
      await openSettings()
      const german = section('German')

      const goal = within(german).getByLabelText('Daily review goal')
      await userEvent.clear(goal)
      await userEvent.type(goal, '5000')
      const length = within(german).getByLabelText('Practice session length')
      await userEvent.clear(length)
      await userEvent.click(
        within(german).getByRole('button', { name: 'Save German settings' }),
      )

      expect(
        await within(german).findByText('Use a number from 1 to 1000'),
      ).toBeInTheDocument()
      expect(within(german).getByText('Enter a number')).toBeInTheDocument()
      expect(fakeApi.requests('/users/me/languages/de')).toHaveLength(0)
    })

    it('removes the language after confirmation and returns to onboarding', async () => {
      let removed = false
      fakeApi.on('DELETE', '/users/me/languages/de', () => {
        removed = true
        return { status: 204 }
      })
      fakeApi.on('GET', '/users/me/languages', () => ({
        status: 200,
        data: removed ? [] : [testLearnedLanguage],
      }))
      const { router } = await openSettings()

      await userEvent.click(
        within(section('German')).getByRole('button', {
          name: 'Remove German',
        }),
      )
      const dialog = screen.getByRole('dialog', { name: 'Remove German?' })
      expect(dialog).toHaveTextContent("It can't be undone.")
      expect(
        within(dialog).getByRole('button', { name: 'Cancel' }),
      ).toHaveFocus()
      await userEvent.click(
        within(dialog).getByRole('button', { name: 'Remove German' }),
      )

      await waitFor(() =>
        expect(router.state.location.pathname).toBe('/onboarding'),
      )
      expect(removed).toBe(true)
    })
  })

  describe('account deletion', () => {
    async function confirmDeletion(password: string) {
      await userEvent.click(
        within(section('Delete account')).getByRole('button', {
          name: 'Delete account',
        }),
      )
      const dialog = screen.getByRole('dialog', {
        name: 'Delete your account?',
      })
      expect(within(dialog).getByLabelText('Password')).toHaveFocus()
      await userEvent.type(within(dialog).getByLabelText('Password'), password)
      await userEvent.click(
        within(dialog).getByRole('button', { name: 'Delete my account' }),
      )
      return dialog
    }

    it('needs the right password', async () => {
      fakeApi.on(
        'DELETE',
        '/users/me',
        validationError('password', 'is incorrect'),
      )
      await openSettings()

      const dialog = await confirmDeletion('wrong-password')

      expect(
        await within(dialog).findByText('Password is incorrect.'),
      ).toBeInTheDocument()
      expect(tokenStore.get()).not.toBeNull()
    })

    it('deletes the account and ends the session', async () => {
      fakeApi.on('DELETE', '/users/me', { status: 204 })
      const { router } = await openSettings()

      await confirmDeletion('correct-password-1')

      await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
      expect(sentBody('DELETE', '/users/me')).toEqual({
        password: 'correct-password-1',
      })
      expect(tokenStore.get()).toBeNull()
      expect(
        screen.getByText('Your account has been deleted'),
      ).toBeInTheDocument()
    })
  })

  it('offers a retry when the settings cannot be loaded', async () => {
    fakeApi.on('GET', '/languages', { status: 0 })
    await renderApp('/settings')

    expect(await findAlert()).toHaveTextContent("Couldn't load your settings")
  })
})
