import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Button } from './Button'
import { ToastProvider } from './Toast'
import { useToast } from './toastContext'
import type { ToastOptions } from './toastContext'

function Trigger({ options }: { options: ToastOptions }) {
  const { toast } = useToast()
  return <Button onClick={() => toast(options)}>Notify</Button>
}

function renderWith(options: ToastOptions) {
  return render(
    <ToastProvider>
      <Trigger options={options} />
    </ToastProvider>,
  )
}

describe('Toast', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('announces normal toasts politely', async () => {
    renderWith({
      title: 'Word saved',
      description: 'der Hund',
      variant: 'success',
    })
    await userEvent.click(screen.getByRole('button', { name: 'Notify' }))
    const region = screen.getByRole('status')
    expect(region).toHaveAttribute('aria-live', 'polite')
    expect(region).toHaveTextContent('Word saved')
    expect(region).toHaveTextContent('der Hund')
  })

  it('announces errors assertively', async () => {
    renderWith({ title: 'Could not save', variant: 'error' })
    await userEvent.click(screen.getByRole('button', { name: 'Notify' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Could not save')
  })

  it('can be dismissed', async () => {
    renderWith({ title: 'Word saved' })
    await userEvent.click(screen.getByRole('button', { name: 'Notify' }))
    await userEvent.click(
      screen.getByRole('button', { name: 'Dismiss notification' }),
    )
    expect(screen.queryByText('Word saved')).not.toBeInTheDocument()
  })

  it('dismisses itself after its duration', () => {
    vi.useFakeTimers()
    renderWith({ title: 'Word saved', duration: 3000 })
    // fireEvent: user-event's async click stalls under fake timers
    fireEvent.click(screen.getByRole('button', { name: 'Notify' }))
    expect(screen.getByText('Word saved')).toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(screen.queryByText('Word saved')).not.toBeInTheDocument()
  })

  it('fails loudly when used outside the provider', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Trigger options={{ title: 'x' }} />)).toThrow(
      /ToastProvider/,
    )
    spy.mockRestore()
  })
})
