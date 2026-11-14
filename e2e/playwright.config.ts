import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests (QA-05) against a running stack, by default the Docker Compose one on
 * http://localhost:3000 with the scripted AI provider:
 *
 *   docker compose -f docker-compose.yml -f docker-compose.e2e.yml up --build -d
 *   cd e2e && npm ci && npm run install-browser && npm test
 *
 * E2E_BASE_URL points the tests at another stack.
 */
export default defineConfig({
  testDir: './tests',
  // One learner journey, step by step: the steps build on each other
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }]]
    : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
