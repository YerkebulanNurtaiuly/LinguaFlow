import { expect, test, type Page } from '@playwright/test'

/**
 * The main learner journey (QA-05): register → onboard → bulk add words → review
 * flashcards → do exercises → see progress. It runs against the real stack, whose AI
 * answers are scripted (docker-compose.e2e.yml), so words get enriched and exercises
 * written without a key.
 */
test('a new learner goes from registration to their progress', async ({
  page,
}) => {
  const email = `e2e-${Date.now()}@example.test`

  await test.step('register', async () => {
    await page.goto('/register')
    await page.getByLabel('Your name').fill('Erika')
    await page.getByLabel('Email').fill(email)
    await page.getByLabel('Password').fill('Lernen macht Spass 2026')
    await page.getByRole('button', { name: 'Create account' }).click()
  })

  await test.step('onboard: German, explained in English, A2, default goal', async () => {
    await expect(page.getByText(/Step 1 of 4/)).toBeVisible()
    await expect(page.getByRole('radio', { name: /German/ })).toBeChecked()
    await page.getByRole('button', { name: 'Continue' }).click()
    await expect(page.getByText(/Step 2 of 4/)).toBeVisible()
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.getByRole('radio', { name: /A2/ }).check()
    await page.getByRole('button', { name: 'Continue' }).click()
    await expect(page.getByText(/Step 4 of 4/)).toBeVisible()
    await page.getByRole('button', { name: 'Start learning' }).click()
    await expect(
      page.getByRole('heading', { level: 1, name: 'Dashboard' }),
    ).toBeVisible()
  })

  await test.step('add three words at once and wait for the AI', async () => {
    await page.goto('/vocabulary/add')
    await page
      .getByLabel('Your words')
      .fill('Hund - dog\nKatze - cat\nlaufen - to run')
    await page.getByRole('button', { name: 'Check the list' }).click()
    await expect(page.getByRole('table')).toBeVisible()
    await page.getByRole('button', { name: 'Add 3 words' }).click()
    await expect(page.getByText('Added 3 words')).toBeVisible()
    await expect(page.getByText('AI has filled in the details')).toBeVisible({
      timeout: 60_000,
    })

    await page.goto('/vocabulary')
    const words = page.getByRole('table')
    await expect(
      words.getByRole('button', { name: 'Katze', exact: true }),
    ).toBeVisible()
    // Filled in by the (scripted) AI
    await expect(words.getByText('Plural: Katzen')).toBeVisible()
    await expect(page.getByText('AI pending')).toHaveCount(0)
  })

  await test.step('review the new flashcards', async () => {
    await page.goto('/flashcards')
    await page.getByRole('button', { name: 'Start review' }).click()
    await reviewUntilDone(page)
    await expect(page.getByText(/cards? in .* · \d+% remembered/)).toBeVisible()
  })

  await test.step('add the Dativ topic from the suggestions', async () => {
    await page.goto('/grammar')
    await page.getByRole('button', { name: 'Browse suggestions' }).click()
    const dialog = page.getByRole('dialog', { name: 'Add grammar topics' })
    await dialog.getByLabel('Find a suggestion').fill('Dativ')
    await dialog.getByRole('button', { name: 'Add Dativ', exact: true }).click()
    await expect(page.getByText('Added “Dativ”')).toBeVisible()
    await page.keyboard.press('Escape')
  })

  await test.step('have exercises written and do them', async () => {
    await page.goto('/practice/exercises')
    await page.getByRole('checkbox', { name: /^Dativ/ }).check()
    // The scripted AI writes fill-in-the-blank, multiple-choice and word-order exercises
    const formats = page.getByRole('group', { name: 'Formats' })
    for (const format of ['Fill in the blank', 'Multiple choice', 'Word order']) {
      await formats.getByRole('checkbox', { name: format }).check()
    }
    for (const format of [
      'Translation',
      'Build a sentence',
      'Fix the mistake',
      'Rewrite the sentence',
    ]) {
      await formats.getByRole('checkbox', { name: format }).uncheck()
    }
    await page.getByRole('combobox', { name: 'How many' }).selectOption('5')
    await page.getByRole('button', { name: 'Create exercises' }).click()

    await expect(page.getByText(/Exercise 1 of 5/)).toBeVisible({
      timeout: 60_000,
    })
    await answerUntilDone(page)
    await expect(page.getByRole('heading', { name: 'Set done' })).toBeVisible()
    await expect(page.getByText(/of 5 right/)).toBeVisible()
  })

  await test.step('see the practice on the progress page', async () => {
    await page.goto('/progress')
    const figures = page.getByRole('region', { name: 'Figures' })
    await expect(figures).toBeVisible()
    await expect(figures).toContainText('on 1 day')
    await expect(
      page.getByRole('list', { name: 'Words by status' }),
    ).toBeVisible()

    await page.goto('/dashboard')
    await expect(
      page.getByRole('heading', { level: 1, name: 'Dashboard' }),
    ).toBeVisible()
  })
})

/** Shows each card's answer and grades it Good until the session sums up. */
async function reviewUntilDone(page: Page) {
  const summary = page.getByText(/cards? in .* · \d+% remembered/)
  for (let shown = 0; shown < 20; shown++) {
    const question = page.getByRole('region', { name: 'Question' })
    await expect(question.or(summary)).toBeVisible()
    if (await summary.isVisible()) return
    await question.getByRole('button', { name: /Show answer/ }).click()
    await expect(page.getByRole('region', { name: 'Answer' })).toBeVisible()
    await page.getByRole('button', { name: /^Good/ }).click()
  }
  throw new Error('The review session never ended')
}

/** Answers each exercise the simple way (first option, words in order, "dem"), then moves on. */
async function answerUntilDone(page: Page) {
  const done = page.getByRole('heading', { name: 'Set done' })
  for (let exercise = 0; exercise < 10; exercise++) {
    const card = page.getByRole('article')
    await expect(card.or(done)).toBeVisible()
    if (await done.isVisible()) return

    const gaps = card.getByRole('textbox', { name: /^Gap/ })
    const options = card.getByRole('radio')
    const words = card.getByRole('button', { name: /^Add “/ })
    if ((await gaps.count()) > 0) {
      for (const gap of await gaps.all()) await gap.fill('dem')
    } else if ((await options.count()) > 0) {
      await options.first().check()
    } else {
      while ((await words.count()) > 0) await words.first().click()
    }
    await card.getByRole('button', { name: 'Check' }).click()
    await expect(page.getByRole('region', { name: 'Result' })).toBeVisible()
    await page.getByRole('button', { name: /^(Next|See results)/ }).click()
  }
  throw new Error('The exercise set never finished')
}
