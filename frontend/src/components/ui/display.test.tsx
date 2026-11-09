import { render, screen } from '@testing-library/react'
import { BookOpen } from 'lucide-react'
import { describe, expect, it } from 'vitest'
import { Badge, CefrBadge, StatusBadge } from './Badge'
import { Button } from './Button'
import { EmptyState } from './EmptyState'
import { Skeleton, SkeletonText } from './Skeleton'
import { Table, TableCaption, TBody, Td, Th, THead, Tr } from './Table'

describe('display components', () => {
  it('renders badges with readable text', () => {
    render(
      <>
        <Badge variant="success">Done</Badge>
        <CefrBadge level="B1" />
        <StatusBadge status="MASTERED" />
      </>,
    )
    expect(screen.getByText('Done')).toBeInTheDocument()
    expect(screen.getByText('B1')).toHaveAttribute('title', 'CEFR level B1')
    expect(screen.getByText('Mastered')).toBeInTheDocument()
  })

  it('renders an accessible table', () => {
    render(
      <Table>
        <TableCaption>Vocabulary</TableCaption>
        <THead>
          <Tr>
            <Th>Word</Th>
          </Tr>
        </THead>
        <TBody>
          <Tr>
            <Td>der Hund</Td>
          </Tr>
        </TBody>
      </Table>,
    )
    expect(
      screen.getByRole('table', { name: 'Vocabulary' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Word' })).toHaveAttribute(
      'scope',
      'col',
    )
    expect(screen.getByRole('cell', { name: 'der Hund' })).toBeInTheDocument()
  })

  it('renders an empty state with its action', () => {
    render(
      <EmptyState
        icon={BookOpen}
        title="No words yet"
        description="Add some."
        action={<Button>Add words</Button>}
      />,
    )
    expect(
      screen.getByRole('heading', { name: 'No words yet' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Add words' }),
    ).toBeInTheDocument()
  })

  it('hides skeletons from assistive tech but announces loading', () => {
    const { container } = render(<Skeleton className="h-4" />)
    expect(container.firstChild).toHaveAttribute('aria-hidden', 'true')
    render(<SkeletonText lines={2} label="Loading words" />)
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('status')).toHaveTextContent('Loading words')
  })
})
