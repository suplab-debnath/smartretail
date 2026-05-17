import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import ChecklistPanel from '../../components/ChecklistPanel'
import type { ChecklistItem } from '../../types'

const items: ChecklistItem[] = [
  { id: '1', text: 'Stock alert raised', matchPattern: 'STOCK_ALERT' },
  { id: '2', text: 'Event published',    matchPattern: 'EVENT_PUBLISHED' },
]

describe('ChecklistPanel', () => {
  it('renders nothing when items is empty', () => {
    const { container } = render(<ChecklistPanel items={[]} hasMatch={() => false} />)
    expect(container.firstChild).toBeNull()
  })

  it('renders checklist item text', () => {
    render(<ChecklistPanel items={items} hasMatch={() => false} />)
    expect(screen.getByText('Stock alert raised')).toBeInTheDocument()
    expect(screen.getByText('Event published')).toBeInTheDocument()
  })

  it('shows checked count as fraction', () => {
    render(<ChecklistPanel items={items} hasMatch={p => p === 'STOCK_ALERT'} />)
    expect(screen.getByText('1/2')).toBeInTheDocument()
  })

  it('shows 0/N when no items match', () => {
    render(<ChecklistPanel items={items} hasMatch={() => false} />)
    expect(screen.getByText('0/2')).toBeInTheDocument()
  })

  it('marks matched items with checkmark', () => {
    render(<ChecklistPanel items={items} hasMatch={p => p === 'STOCK_ALERT'} />)
    const marks = screen.getAllByText('✓')
    expect(marks).toHaveLength(1)
  })

  it('marks unmatched items with circle', () => {
    render(<ChecklistPanel items={items} hasMatch={() => false} />)
    const circles = screen.getAllByText('○')
    expect(circles).toHaveLength(2)
  })
})
