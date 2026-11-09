import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Tabs } from './Tabs'

const items = [
  { id: 'words', label: 'Words', content: <p>Word list</p> },
  { id: 'grammar', label: 'Grammar', content: <p>Grammar list</p> },
  { id: 'sessions', label: 'Sessions', content: <p>Session list</p> },
]

describe('Tabs', () => {
  it('follows the tabs pattern with one tab in the Tab order', () => {
    render(<Tabs label="Material" items={items} />)
    expect(
      screen.getByRole('tablist', { name: 'Material' }),
    ).toBeInTheDocument()
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((t) => t.getAttribute('tabindex'))).toEqual([
      '0',
      '-1',
      '-1',
    ])
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel', { name: 'Words' })).toHaveTextContent(
      'Word list',
    )
  })

  it('selects on click and reports the change', async () => {
    const onChange = vi.fn()
    render(<Tabs label="Material" items={items} onChange={onChange} />)
    await userEvent.click(screen.getByRole('tab', { name: 'Grammar' }))
    expect(screen.getByRole('tab', { name: 'Grammar' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.getByRole('tabpanel', { name: 'Grammar' })).toHaveTextContent(
      'Grammar list',
    )
    expect(onChange).toHaveBeenCalledWith('grammar')
  })

  it('supports arrow keys (wrapping), Home and End', async () => {
    render(<Tabs label="Material" items={items} />)
    screen.getByRole('tab', { name: 'Words' }).focus()

    await userEvent.keyboard('{ArrowLeft}')
    expect(screen.getByRole('tab', { name: 'Sessions' })).toHaveFocus()
    expect(screen.getByRole('tab', { name: 'Sessions' })).toHaveAttribute(
      'aria-selected',
      'true',
    )

    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Words' })).toHaveFocus()

    await userEvent.keyboard('{End}')
    expect(screen.getByRole('tab', { name: 'Sessions' })).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(screen.getByRole('tab', { name: 'Words' })).toHaveFocus()
  })
})
