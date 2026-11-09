import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { Field } from './Field'
import { Input } from './Input'
import { Select } from './Select'
import { Textarea } from './Textarea'

describe('Field', () => {
  it('labels the control and links the hint', () => {
    render(
      <Field label="Word" hint="With article">
        <Input />
      </Field>,
    )
    const input = screen.getByLabelText('Word')
    expect(input).toHaveAccessibleDescription('With article')
    expect(input).not.toHaveAttribute('aria-invalid')
  })

  it('marks the control invalid and announces the error', () => {
    render(
      <Field label="Translation" hint="English" error="Translation is required">
        <Input />
      </Field>,
    )
    const input = screen.getByLabelText('Translation')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('English Translation is required')
  })

  it('passes required to the control and keeps an explicit id', () => {
    render(
      <Field label="Email" required>
        <Input id="email" type="email" />
      </Field>,
    )
    const input = screen.getByLabelText(/Email/)
    expect(input).toBeRequired()
    expect(input).toHaveAttribute('id', 'email')
  })

  it('works with Select and Textarea', async () => {
    render(
      <>
        <Field label="Level">
          <Select
            placeholder="Choose…"
            options={[
              { value: 'A1', label: 'A1' },
              { value: 'B1', label: 'B1' },
            ]}
          />
        </Field>
        <Field label="Notes">
          <Textarea />
        </Field>
      </>,
    )
    const select = screen.getByLabelText('Level')
    await userEvent.selectOptions(select, 'B1')
    expect(select).toHaveValue('B1')
    await userEvent.type(screen.getByLabelText('Notes'), 'Dativ!')
    expect(screen.getByLabelText('Notes')).toHaveValue('Dativ!')
  })
})
