import { screen, waitFor } from '@testing-library/react'
import { expect } from 'vitest'

/**
 * Alerts with content. The toast provider keeps an (empty) role="alert" live region on
 * every page, so `getByRole('alert')` alone would always find two.
 */
export function queryAlerts(): HTMLElement[] {
  return screen
    .queryAllByRole('alert')
    .filter((alert) => alert.textContent?.trim())
}

/** Waits for exactly one alert with content and returns it. */
export async function findAlert(): Promise<HTMLElement> {
  return waitFor(() => {
    const alerts = queryAlerts()
    expect(alerts).toHaveLength(1)
    return alerts[0]!
  })
}
